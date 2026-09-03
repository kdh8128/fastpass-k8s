package com.fastpass.api.queue;

import com.fastpass.api.common.exception.DuplicateApplicationException;
import com.fastpass.api.common.exception.NotFoundException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ApplicationQueueService {

    private static final String APPLICATION_QUEUE_KEY =
            "fastpass:application:queue";

    private static final String APPLICATION_SEQUENCE_KEY =
            "fastpass:application:sequence";

    private static final String APPLICATION_DATA_PREFIX =
            "fastpass:application:data:";

    private static final String APPLICATION_DUPLICATE_PREFIX =
            "fastpass:application:duplicate:";

    private static final String EVENT_EXISTS_PREFIX =
            "fastpass:event:exists:";

    private static final DefaultRedisScript<String> ACCEPT_SCRIPT =
            new DefaultRedisScript<>(
                    """
                    if redis.call('EXISTS', KEYS[1]) == 0 then
                        return 'NOT_FOUND'
                    end

                    local duplicateResult =
                        redis.call('SET', KEYS[2], '1', 'NX')

                    if not duplicateResult then
                        return 'DUPLICATE'
                    end

                    local applicationId =
                        redis.call('INCR', KEYS[3])

                    local applicationKey =
                        ARGV[1] .. applicationId

                    redis.call(
                        'HSET',
                        applicationKey,
                        'eventId', ARGV[2],
                        'applicantName', ARGV[3],
                        'createdAt', ARGV[4],
                        'status', 'PENDING'
                    )

                    redis.call(
                        'RPUSH',
                        KEYS[4],
                        tostring(applicationId)
                    )

                    return 'OK:' .. applicationId
                    """,
                    String.class
            );

    private static final DefaultRedisScript<Long> SEQUENCE_SCRIPT =
            new DefaultRedisScript<>(
                    """
                    local current =
                        tonumber(redis.call('GET', KEYS[1]) or '0')

                    local target =
                        tonumber(ARGV[1])

                    if current < target then
                        redis.call('SET', KEYS[1], target)
                    end

                    return 1
                    """,
                    Long.class
            );

    private final StringRedisTemplate redisTemplate;

    public ApplicationQueueService(
            StringRedisTemplate redisTemplate
    ) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 신규 신청을 Redis에서 원자적으로 처리한다.
     *
     * 1. 이벤트 존재 여부 확인
     * 2. 중복 신청 확인
     * 3. applicationId 생성
     * 4. PENDING 데이터 저장
     * 5. Queue 적재
     */
    public AcceptedApplication accept(
            Long eventId,
            String applicantName
    ) {
        LocalDateTime createdAt = LocalDateTime.now();

        String eventKey =
                EVENT_EXISTS_PREFIX + eventId;

        String duplicateKey =
                APPLICATION_DUPLICATE_PREFIX
                        + eventId
                        + ":"
                        + applicantName;

        String result = redisTemplate.execute(
                ACCEPT_SCRIPT,
                List.of(
                        eventKey,
                        duplicateKey,
                        APPLICATION_SEQUENCE_KEY,
                        APPLICATION_QUEUE_KEY
                ),
                APPLICATION_DATA_PREFIX,
                String.valueOf(eventId),
                applicantName,
                createdAt.toString()
        );

        if (result == null) {
            throw new IllegalStateException(
                    "Redis application acceptance failed."
            );
        }

        if ("NOT_FOUND".equals(result)) {
            throw new NotFoundException(
                    "Event not found. id=" + eventId
            );
        }

        if ("DUPLICATE".equals(result)) {
            throw new DuplicateApplicationException(
                    "Already applied to this event. eventId="
                            + eventId
                            + ", applicantName="
                            + applicantName
            );
        }

        if (!result.startsWith("OK:")) {
            throw new IllegalStateException(
                    "Unexpected Redis result: " + result
            );
        }

        Long applicationId =
                Long.parseLong(result.substring(3));

        return new AcceptedApplication(
                applicationId,
                createdAt
        );
    }

    public Long dequeue() {
        String value = redisTemplate
                .opsForList()
                .leftPop(APPLICATION_QUEUE_KEY);

        if (value == null) {
            return null;
        }

        return Long.parseLong(value);
    }

    public List<Long> dequeueBatch(int batchSize) {
        List<Long> applicationIds =
                new ArrayList<>();

        for (int i = 0; i < batchSize; i++) {
            Long applicationId = dequeue();

            if (applicationId == null) {
                break;
            }

            applicationIds.add(applicationId);
        }

        return applicationIds;
    }

    public void requeue(Long applicationId) {
        redisTemplate
                .opsForList()
                .rightPush(
                        APPLICATION_QUEUE_KEY,
                        String.valueOf(applicationId)
                );
    }

    public PendingApplication getPendingApplication(
            Long applicationId
    ) {
        Map<Object, Object> values =
                redisTemplate
                        .opsForHash()
                        .entries(
                                APPLICATION_DATA_PREFIX
                                        + applicationId
                        );

        if (values.isEmpty()) {
            return null;
        }

        Object eventId = values.get("eventId");
        Object applicantName =
                values.get("applicantName");
        Object createdAt =
                values.get("createdAt");

        if (eventId == null
                || applicantName == null
                || createdAt == null) {
            return null;
        }

        return new PendingApplication(
                applicationId,
                Long.parseLong(eventId.toString()),
                applicantName.toString(),
                LocalDateTime.parse(
                        createdAt.toString()
                )
        );
    }

    public void completePending(
            Long applicationId
    ) {
        redisTemplate.delete(
                APPLICATION_DATA_PREFIX
                        + applicationId
        );
    }

    public void registerEvent(Long eventId) {
        redisTemplate
                .opsForValue()
                .set(
                        EVENT_EXISTS_PREFIX + eventId,
                        "1"
                );
    }

    public void registerDuplicate(
            Long eventId,
            String applicantName
    ) {
        redisTemplate
                .opsForValue()
                .set(
                        APPLICATION_DUPLICATE_PREFIX
                                + eventId
                                + ":"
                                + applicantName,
                        "1"
                );
    }

    public void ensureSequenceAtLeast(
            Long value
    ) {
        if (value == null) {
            return;
        }

        redisTemplate.execute(
                SEQUENCE_SCRIPT,
                List.of(APPLICATION_SEQUENCE_KEY),
                String.valueOf(value)
        );
    }

    public Long getQueueSize() {
        Long size = redisTemplate
                .opsForList()
                .size(APPLICATION_QUEUE_KEY);

        return size == null ? 0L : size;
    }

    public record AcceptedApplication(
            Long applicationId,
            LocalDateTime createdAt
    ) {
    }
}
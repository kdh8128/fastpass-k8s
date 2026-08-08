package com.fastpass.api.queue;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ApplicationQueueService {

    private static final String APPLICATION_QUEUE_KEY = "fastpass:application:queue";

    private final StringRedisTemplate redisTemplate;

    public ApplicationQueueService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void enqueue(Long applicationId) {
        redisTemplate.opsForList().rightPush(APPLICATION_QUEUE_KEY, String.valueOf(applicationId));
    }

    public Long dequeue() {
        String value = redisTemplate.opsForList().leftPop(APPLICATION_QUEUE_KEY);

        if (value == null) {
            return null;
        }

        return Long.parseLong(value);
    }

    public List<Long> dequeueBatch(int batchSize) {
        List<Long> applicationIds = new ArrayList<>();

        for (int i = 0; i < batchSize; i++) {
            Long applicationId = dequeue();

            if (applicationId == null) {
                break;
            }

            applicationIds.add(applicationId);
        }

        return applicationIds;
    }

    public Long getQueueSize() {
        Long size = redisTemplate.opsForList().size(APPLICATION_QUEUE_KEY);
        return size == null ? 0L : size;
    }
}
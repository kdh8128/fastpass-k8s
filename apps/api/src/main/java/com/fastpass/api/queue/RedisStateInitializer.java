package com.fastpass.api.queue;

import com.fastpass.api.application.EventApplication;
import com.fastpass.api.application.EventApplicationRepository;
import com.fastpass.api.event.EventRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(
        name = "fastpass.redis-state-initializer.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class RedisStateInitializer {

    private final EventRepository eventRepository;
    private final EventApplicationRepository applicationRepository;
    private final ApplicationQueueService applicationQueueService;

    public RedisStateInitializer(
            EventRepository eventRepository,
            EventApplicationRepository applicationRepository,
            ApplicationQueueService applicationQueueService
    ) {
        this.eventRepository = eventRepository;
        this.applicationRepository = applicationRepository;
        this.applicationQueueService = applicationQueueService;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional(readOnly = true)
    public void initializeRedisState() {

        eventRepository.findAll()
                .forEach(event ->
                        applicationQueueService.registerEvent(
                                event.getId()
                        )
                );

        for (EventApplication application
                : applicationRepository.findAll()) {

            applicationQueueService.registerDuplicate(
                    application.getEvent().getId(),
                    application.getApplicantName()
            );
        }

        /*
         * Redis에서 생성하는 외부 applicationId가
         * 기존 PostgreSQL PK(id)와도 충돌하지 않게 한다.
         */
        Long maxRequestId =
                applicationRepository.findMaxRequestId();

        Long maxDatabaseId =
                applicationRepository.findMaxId();

        long sequenceFloor =
                Math.max(
                        maxRequestId == null ? 0L : maxRequestId,
                        maxDatabaseId == null ? 0L : maxDatabaseId
                );

        applicationQueueService.ensureSequenceAtLeast(
                sequenceFloor
        );
    }
}
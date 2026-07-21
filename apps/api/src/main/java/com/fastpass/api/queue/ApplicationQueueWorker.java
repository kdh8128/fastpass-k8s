package com.fastpass.api.queue;

import com.fastpass.api.application.EventApplication;
import com.fastpass.api.application.EventApplicationRepository;
import com.fastpass.api.common.exception.NotFoundException;
import com.fastpass.api.event.Event;
import com.fastpass.api.event.EventRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ApplicationQueueWorker {

    private final ApplicationQueueService applicationQueueService;
    private final EventApplicationRepository applicationRepository;
    private final EventRepository eventRepository;

    public ApplicationQueueWorker(
            ApplicationQueueService applicationQueueService,
            EventApplicationRepository applicationRepository,
            EventRepository eventRepository
    ) {
        this.applicationQueueService = applicationQueueService;
        this.applicationRepository = applicationRepository;
        this.eventRepository = eventRepository;
    }

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void processApplicationQueue() {
        Long applicationId = applicationQueueService.dequeue();

        if (applicationId == null) {
            return;
        }

        EventApplication application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new NotFoundException("Application not found. id=" + applicationId));

        Event event = eventRepository.findByIdForUpdate(application.getEvent().getId())
                .orElseThrow(() -> new NotFoundException("Event not found. id=" + application.getEvent().getId()));

        if (event.isFull()) {
            application.markFailed();
            return;
        }

        event.increaseAppliedCount();
        application.markSuccess();
    }
}

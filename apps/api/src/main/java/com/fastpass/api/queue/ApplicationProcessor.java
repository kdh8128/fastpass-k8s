package com.fastpass.api.queue;

import com.fastpass.api.application.EventApplication;
import com.fastpass.api.application.EventApplicationRepository;
import com.fastpass.api.common.exception.NotFoundException;
import com.fastpass.api.event.Event;
import com.fastpass.api.event.EventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApplicationProcessor {

    private final EventApplicationRepository applicationRepository;
    private final EventRepository eventRepository;

    public ApplicationProcessor(
            EventApplicationRepository applicationRepository,
            EventRepository eventRepository
    ) {
        this.applicationRepository = applicationRepository;
        this.eventRepository = eventRepository;
    }

    @Transactional
    public void process(Long applicationId) {
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
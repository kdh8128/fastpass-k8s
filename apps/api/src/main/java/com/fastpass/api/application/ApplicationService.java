package com.fastpass.api.application;

import com.fastpass.api.application.dto.ApplicationRequest;
import com.fastpass.api.application.dto.ApplicationResponse;
import com.fastpass.api.common.exception.DuplicateApplicationException;
import com.fastpass.api.common.exception.NotFoundException;
import com.fastpass.api.event.Event;
import com.fastpass.api.event.EventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApplicationService {

    private final EventRepository eventRepository;
    private final EventApplicationRepository applicationRepository;

    public ApplicationService(
            EventRepository eventRepository,
            EventApplicationRepository applicationRepository
    ) {
        this.eventRepository = eventRepository;
        this.applicationRepository = applicationRepository;
    }

    @Transactional
    public ApplicationResponse apply(Long eventId, ApplicationRequest request) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new NotFoundException("Event not found. id=" + eventId));

        if (applicationRepository.existsByEvent_IdAndApplicantName(eventId, request.applicantName())) {
            throw new DuplicateApplicationException(
                    "Already applied to this event. eventId=" + eventId + ", applicantName=" + request.applicantName()
            );
        }

        ApplicationStatus status;

        if (event.isFull()) {
            status = ApplicationStatus.FAILED;
        } else {
            event.increaseAppliedCount();
            status = ApplicationStatus.SUCCESS;
        }

        EventApplication application = new EventApplication(
                event,
                request.applicantName(),
                status
        );

        EventApplication savedApplication = applicationRepository.save(application);

        return ApplicationResponse.from(savedApplication);
    }

    @Transactional(readOnly = true)
    public ApplicationResponse getApplication(Long applicationId) {
        EventApplication application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new NotFoundException("Application not found. id=" + applicationId));

        return ApplicationResponse.from(application);
    }
}
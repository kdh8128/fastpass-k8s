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
public class ApplicationPersistenceService {

    private final EventRepository eventRepository;
    private final EventApplicationRepository applicationRepository;

    public ApplicationPersistenceService(
            EventRepository eventRepository,
            EventApplicationRepository applicationRepository
    ) {
        this.eventRepository = eventRepository;
        this.applicationRepository = applicationRepository;
    }

    @Transactional
    public CreatedApplication createPending(
            Long eventId,
            ApplicationRequest request
    ) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() ->
                        new NotFoundException("Event not found. id=" + eventId)
                );

        if (applicationRepository.existsByEvent_IdAndApplicantName(
                eventId,
                request.applicantName()
        )) {
            throw new DuplicateApplicationException(
                    "Already applied to this event. eventId="
                            + eventId
                            + ", applicantName="
                            + request.applicantName()
            );
        }

        EventApplication application = new EventApplication(
                event,
                request.applicantName(),
                ApplicationStatus.PENDING
        );

        EventApplication savedApplication =
                applicationRepository.save(application);

        return new CreatedApplication(
                savedApplication.getId(),
                ApplicationResponse.from(savedApplication)
        );
    }

    public record CreatedApplication(
            Long applicationId,
            ApplicationResponse response
    ) {
    }
}
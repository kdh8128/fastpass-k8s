package com.fastpass.api.application;

import com.fastpass.api.application.dto.ApplicationRequest;
import com.fastpass.api.application.dto.ApplicationResponse;
import com.fastpass.api.common.exception.DuplicateApplicationException;
import com.fastpass.api.common.exception.NotFoundException;
import com.fastpass.api.event.Event;
import com.fastpass.api.event.EventRepository;
import org.springframework.dao.DataIntegrityViolationException;
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
                        new NotFoundException(
                                "Event not found. id=" + eventId
                        )
                );

        EventApplication application = new EventApplication(
                event,
                request.applicantName(),
                ApplicationStatus.PENDING
        );

        try {
            /*
             * 중복 확인 SELECT를 별도로 수행하지 않는다.
             *
             * DB의 UNIQUE 제약:
             * (event_id, applicant_name)
             *
             * 을 이용해 중복 신청을 최종적으로 차단한다.
             *
             * saveAndFlush()를 사용하는 이유:
             * INSERT를 이 위치에서 즉시 실행해서
             * UNIQUE violation을 try-catch 내부에서 잡기 위함이다.
             */
            EventApplication savedApplication =
                    applicationRepository.saveAndFlush(application);

            return new CreatedApplication(
                    savedApplication.getId(),
                    ApplicationResponse.from(savedApplication)
            );

        } catch (DataIntegrityViolationException e) {
            throw new DuplicateApplicationException(
                    "Already applied to this event. eventId="
                            + eventId
                            + ", applicantName="
                            + request.applicantName()
            );
        }
    }

    public record CreatedApplication(
            Long applicationId,
            ApplicationResponse response
    ) {
    }
}
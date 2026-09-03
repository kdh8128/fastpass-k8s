package com.fastpass.api.queue;

import com.fastpass.api.application.ApplicationStatus;
import com.fastpass.api.application.EventApplication;
import com.fastpass.api.application.EventApplicationRepository;
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
        this.applicationRepository =
                applicationRepository;

        this.eventRepository =
                eventRepository;
    }

    @Transactional
    public void process(
            PendingApplication pending
    ) {
        /*
         * 실제 SELECT 없이 FK 참조용 proxy만 얻는다.
         *
         * 이벤트 존재 여부는 Redis 접수 단계에서 이미 확인한다.
         */
        Event event =
                eventRepository.getReferenceById(
                        pending.eventId()
                );

        /*
         * 정원 확인 + appliedCount 증가를
         * 조건부 atomic UPDATE 한 번으로 처리한다.
         */
        int updatedRows =
                eventRepository
                        .tryIncreaseAppliedCount(
                                pending.eventId()
                        );

        ApplicationStatus status =
                updatedRows == 1
                        ? ApplicationStatus.SUCCESS
                        : ApplicationStatus.FAILED;

        EventApplication application =
                new EventApplication(
                        pending.applicationId(),
                        event,
                        pending.applicantName(),
                        status,
                        pending.createdAt()
                );

        /*
         * INSERT 실패 시 같은 transaction의
         * capacity 증가도 rollback된다.
         */
        applicationRepository.save(
                application
        );
    }
}
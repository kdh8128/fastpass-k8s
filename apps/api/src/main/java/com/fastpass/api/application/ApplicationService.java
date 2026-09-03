package com.fastpass.api.application;

import com.fastpass.api.application.dto.ApplicationRequest;
import com.fastpass.api.application.dto.ApplicationResponse;
import com.fastpass.api.common.exception.NotFoundException;
import com.fastpass.api.queue.ApplicationQueueService;
import com.fastpass.api.queue.PendingApplication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApplicationService {

    private final EventApplicationRepository applicationRepository;
    private final ApplicationQueueService applicationQueueService;

    public ApplicationService(
            EventApplicationRepository applicationRepository,
            ApplicationQueueService applicationQueueService
    ) {
        this.applicationRepository =
                applicationRepository;

        this.applicationQueueService =
                applicationQueueService;
    }

    /**
     * Redis-first application acceptance.
     *
     * PostgreSQL을 사용하지 않고
     * Redis에서 접수를 완료한 뒤 즉시 PENDING을 반환한다.
     */
    public ApplicationResponse apply(
            Long eventId,
            ApplicationRequest request
    ) {
        ApplicationQueueService.AcceptedApplication accepted =
                applicationQueueService.accept(
                        eventId,
                        request.applicantName()
                );

        return ApplicationResponse.pending(
                accepted.applicationId(),
                eventId,
                request.applicantName(),
                accepted.createdAt()
        );
    }

    @Transactional(readOnly = true)
    public ApplicationResponse getApplication(
            Long applicationId
    ) {
        /*
         * 아직 Worker가 처리하지 않은 신청이면
         * Redis에서 PENDING 상태를 반환한다.
         */
        PendingApplication pending =
                applicationQueueService
                        .getPendingApplication(
                                applicationId
                        );

        if (pending != null) {
            return ApplicationResponse.pending(
                    pending.applicationId(),
                    pending.eventId(),
                    pending.applicantName(),
                    pending.createdAt()
            );
        }

        /*
         * Worker 처리가 완료됐으면
         * PostgreSQL에서 조회한다.
         */
        EventApplication application =
                applicationRepository
                        .findByRequestId(applicationId)
                        .orElseGet(() ->
                                applicationRepository
                                        .findById(applicationId)
                                        .orElseThrow(() ->
                                                new NotFoundException(
                                                        "Application not found. id="
                                                                + applicationId
                                                )
                                        )
                        );

        return ApplicationResponse.from(
                application
        );
    }
}
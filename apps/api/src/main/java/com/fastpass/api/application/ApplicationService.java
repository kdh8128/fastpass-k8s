package com.fastpass.api.application;

import com.fastpass.api.application.dto.ApplicationRequest;
import com.fastpass.api.application.dto.ApplicationResponse;
import com.fastpass.api.common.exception.NotFoundException;
import com.fastpass.api.queue.ApplicationQueueService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApplicationService {

    private final EventApplicationRepository applicationRepository;
    private final ApplicationQueueService applicationQueueService;
    private final ApplicationPersistenceService applicationPersistenceService;

    public ApplicationService(
            EventApplicationRepository applicationRepository,
            ApplicationQueueService applicationQueueService,
            ApplicationPersistenceService applicationPersistenceService
    ) {
        this.applicationRepository = applicationRepository;
        this.applicationQueueService = applicationQueueService;
        this.applicationPersistenceService = applicationPersistenceService;
    }

    public ApplicationResponse apply(Long eventId, ApplicationRequest request) {

        ApplicationPersistenceService.CreatedApplication created =
                applicationPersistenceService.createPending(eventId, request);

        applicationQueueService.enqueue(created.applicationId());

        return created.response();
    }

    @Transactional(readOnly = true)
    public ApplicationResponse getApplication(Long applicationId) {
        EventApplication application = applicationRepository.findById(applicationId)
                .orElseThrow(() ->
                        new NotFoundException(
                                "Application not found. id=" + applicationId
                        )
                );

        return ApplicationResponse.from(application);
    }
}
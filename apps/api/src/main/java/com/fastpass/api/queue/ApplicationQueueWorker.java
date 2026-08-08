package com.fastpass.api.queue;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ApplicationQueueWorker {

    private static final int BATCH_SIZE = 50;

    private final ApplicationQueueService applicationQueueService;
    private final ApplicationProcessor applicationProcessor;

    public ApplicationQueueWorker(
            ApplicationQueueService applicationQueueService,
            ApplicationProcessor applicationProcessor
    ) {
        this.applicationQueueService = applicationQueueService;
        this.applicationProcessor = applicationProcessor;
    }

    @Scheduled(fixedDelay = 1000)
    public void processApplicationQueue() {
        List<Long> applicationIds = applicationQueueService.dequeueBatch(BATCH_SIZE);

        if (applicationIds.isEmpty()) {
            return;
        }

        for (Long applicationId : applicationIds) {
            try {
                applicationProcessor.process(applicationId);
            } catch (Exception e) {
                System.err.println("Failed to process application. applicationId=" + applicationId + ", message=" + e.getMessage());
            }
        }
    }
}
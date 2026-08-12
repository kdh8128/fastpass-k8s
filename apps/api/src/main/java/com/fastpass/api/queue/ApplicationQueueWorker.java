package com.fastpass.api.queue;

import com.fastpass.api.metric.FastPassMetrics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(
        name = "fastpass.worker.enabled",
        havingValue = "true"
)
public class ApplicationQueueWorker {

    private static final int BATCH_SIZE = 50;

    private final ApplicationQueueService applicationQueueService;
    private final ApplicationProcessor applicationProcessor;
    private final FastPassMetrics fastPassMetrics;

    public ApplicationQueueWorker(
            ApplicationQueueService applicationQueueService,
            ApplicationProcessor applicationProcessor,
            FastPassMetrics fastPassMetrics
    ) {
        this.applicationQueueService = applicationQueueService;
        this.applicationProcessor = applicationProcessor;
        this.fastPassMetrics = fastPassMetrics;

        System.out.println("FastPass queue worker is enabled. batchSize=" + BATCH_SIZE);
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
                fastPassMetrics.incrementWorkerProcessed();
            } catch (Exception e) {
                fastPassMetrics.incrementWorkerProcessingFailed();

                System.err.println(
                        "Failed to process application. applicationId="
                                + applicationId
                                + ", message="
                                + e.getMessage()
                );
            }
        }
    }
}
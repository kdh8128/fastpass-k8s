package com.fastpass.api.metric;

import com.fastpass.api.queue.ApplicationQueueService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class FastPassMetrics {

    private final Counter workerProcessedCounter;
    private final Counter workerProcessingFailedCounter;

    public FastPassMetrics(
            MeterRegistry meterRegistry,
            ApplicationQueueService applicationQueueService
    ) {
        Gauge.builder(
                        "fastpass_queue_size",
                        applicationQueueService,
                        queueService -> queueService.getQueueSize().doubleValue()
                )
                .description("Current size of FastPass application queue")
                .register(meterRegistry);

        this.workerProcessedCounter = Counter.builder("fastpass_worker_processed_total")
                .description("Total number of applications processed by FastPass worker")
                .register(meterRegistry);

        this.workerProcessingFailedCounter = Counter.builder("fastpass_worker_processing_failed_total")
                .description("Total number of application processing failures in FastPass worker")
                .register(meterRegistry);
    }

    public void incrementWorkerProcessed() {
        workerProcessedCounter.increment();
    }

    public void incrementWorkerProcessingFailed() {
        workerProcessingFailedCounter.increment();
    }
}
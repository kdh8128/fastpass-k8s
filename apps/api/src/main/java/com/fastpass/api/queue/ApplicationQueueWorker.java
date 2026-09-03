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
        this.applicationQueueService =
                applicationQueueService;

        this.applicationProcessor =
                applicationProcessor;

        this.fastPassMetrics =
                fastPassMetrics;

        System.out.println(
                "FastPass queue worker is enabled. batchSize="
                        + BATCH_SIZE
        );
    }

    @Scheduled(fixedDelay = 1000)
    public void processApplicationQueue() {
        List<Long> applicationIds =
                applicationQueueService
                        .dequeueBatch(
                                BATCH_SIZE
                        );

        if (applicationIds.isEmpty()) {
            return;
        }

        for (Long applicationId
                : applicationIds) {

            PendingApplication pending =
                    applicationQueueService
                            .getPendingApplication(
                                    applicationId
                            );

            if (pending == null) {
                continue;
            }

            try {
                /*
                 * Redis 조회는 DB Transaction 밖에서 수행한다.
                 */
                applicationProcessor.process(
                        pending
                );

                /*
                 * DB commit 성공 후
                 * Redis 임시 PENDING 정보를 제거한다.
                 */
                applicationQueueService
                        .completePending(
                                applicationId
                        );

                fastPassMetrics
                        .incrementWorkerProcessed();

            } catch (Exception e) {

                fastPassMetrics
                        .incrementWorkerProcessingFailed();

                /*
                 * DB 처리 실패 시 요청 유실 방지를 위해
                 * Queue에 다시 넣는다.
                 */
                applicationQueueService
                        .requeue(
                                applicationId
                        );

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
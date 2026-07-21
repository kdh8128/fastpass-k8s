package com.fastpass.api.queue;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class QueueStatusController {

    private final ApplicationQueueService applicationQueueService;

    public QueueStatusController(ApplicationQueueService applicationQueueService) {
        this.applicationQueueService = applicationQueueService;
    }

    @GetMapping("/api/queue/applications/size")
    public QueueSizeResponse getApplicationQueueSize() {
        return new QueueSizeResponse(applicationQueueService.getQueueSize());
    }

    public record QueueSizeResponse(Long size) {
    }
}

package com.fastpass.api.queue;

import java.time.LocalDateTime;

public record PendingApplication(
        Long applicationId,
        Long eventId,
        String applicantName,
        LocalDateTime createdAt
) {
}
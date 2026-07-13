package com.fastpass.api.application.dto;

import com.fastpass.api.application.ApplicationStatus;
import com.fastpass.api.application.EventApplication;

import java.time.LocalDateTime;

public record ApplicationResponse(
        Long applicationId,
        Long eventId,
        String applicantName,
        ApplicationStatus status,
        LocalDateTime createdAt
) {
    public static ApplicationResponse from(EventApplication application) {
        return new ApplicationResponse(
                application.getId(),
                application.getEvent().getId(),
                application.getApplicantName(),
                application.getStatus(),
                application.getCreatedAt()
        );
    }
}

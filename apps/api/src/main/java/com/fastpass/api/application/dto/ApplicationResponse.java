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

    public static ApplicationResponse from(
            EventApplication application
    ) {
        Long externalApplicationId =
                application.getRequestId() != null
                        ? application.getRequestId()
                        : application.getId();

        return new ApplicationResponse(
                externalApplicationId,
                application.getEvent().getId(),
                application.getApplicantName(),
                application.getStatus(),
                application.getCreatedAt()
        );
    }

    public static ApplicationResponse pending(
            Long applicationId,
            Long eventId,
            String applicantName,
            LocalDateTime createdAt
    ) {
        return new ApplicationResponse(
                applicationId,
                eventId,
                applicantName,
                ApplicationStatus.PENDING,
                createdAt
        );
    }
}
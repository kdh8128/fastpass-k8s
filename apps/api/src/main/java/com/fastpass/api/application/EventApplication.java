package com.fastpass.api.application;

import com.fastpass.api.event.Event;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "event_applications",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_event_applicant",
                        columnNames = {
                                "event_id",
                                "applicant_name"
                        }
                ),
                @UniqueConstraint(
                        name = "uk_application_request_id",
                        columnNames = {
                                "request_id"
                        }
                )
        }
)
public class EventApplication {

    @Id
    @GeneratedValue(
            strategy = GenerationType.IDENTITY
    )
    private Long id;

    @Column(name = "request_id")
    private Long requestId;

    private String applicantName;

    @Enumerated(EnumType.STRING)
    private ApplicationStatus status;

    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id")
    private Event event;

    protected EventApplication() {
    }

    /*
     * 기존 코드/테스트 호환용 생성자
     */
    public EventApplication(
            Event event,
            String applicantName,
            ApplicationStatus status
    ) {
        this(
                null,
                event,
                applicantName,
                status,
                LocalDateTime.now()
        );
    }

    /*
     * Redis-first Worker용 생성자
     */
    public EventApplication(
            Long requestId,
            Event event,
            String applicantName,
            ApplicationStatus status,
            LocalDateTime createdAt
    ) {
        this.requestId = requestId;
        this.event = event;
        this.applicantName = applicantName;
        this.status = status;
        this.createdAt = createdAt;
    }

    public void markSuccess() {
        this.status =
                ApplicationStatus.SUCCESS;
    }

    public void markFailed() {
        this.status =
                ApplicationStatus.FAILED;
    }

    public Long getId() {
        return id;
    }

    public Long getRequestId() {
        return requestId;
    }

    public String getApplicantName() {
        return applicantName;
    }

    public ApplicationStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public Event getEvent() {
        return event;
    }
}
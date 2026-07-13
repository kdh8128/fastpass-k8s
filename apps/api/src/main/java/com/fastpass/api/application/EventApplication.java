package com.fastpass.api.application;

import com.fastpass.api.event.Event;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "event_applications")
public class EventApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String applicantName;

    @Enumerated(EnumType.STRING)
    private ApplicationStatus status;

    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id")
    private Event event;

    protected EventApplication() {
    }

    public EventApplication(Event event, String applicantName, ApplicationStatus status) {
        this.event = event;
        this.applicantName = applicantName;
        this.status = status;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
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

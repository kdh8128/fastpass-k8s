package com.fastpass.api.event;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "events")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;

    private String description;

    private int capacity;

    private int appliedCount;

    private LocalDateTime eventStartAt;

    private LocalDateTime createdAt;

    protected Event() {
    }

    public Event(String title, String description, int capacity, LocalDateTime eventStartAt) {
        this.title = title;
        this.description = description;
        this.capacity = capacity;
        this.appliedCount = 0;
        this.eventStartAt = eventStartAt;
        this.createdAt = LocalDateTime.now();
    }

    public boolean isFull() {
        return appliedCount >= capacity;
    }

    public void increaseAppliedCount() {
        if (isFull()) {
            throw new IllegalStateException("Event capacity is already full.");
        }
        this.appliedCount++;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public int getCapacity() {
        return capacity;
    }

    public int getAppliedCount() {
        return appliedCount;
    }

    public LocalDateTime getEventStartAt() {
        return eventStartAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
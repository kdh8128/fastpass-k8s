package com.fastpass.api.event.dto;

import com.fastpass.api.event.Event;

import java.time.LocalDateTime;

public record EventResponse(
        Long id,
        String title,
        String description,
        int capacity,
        int appliedCount,
        LocalDateTime eventStartAt,
        LocalDateTime createdAt
) {
    public static EventResponse from(Event event) {
        return new EventResponse(
                event.getId(),
                event.getTitle(),
                event.getDescription(),
                event.getCapacity(),
                event.getAppliedCount(),
                event.getEventStartAt(),
                event.getCreatedAt()
        );
    }
}
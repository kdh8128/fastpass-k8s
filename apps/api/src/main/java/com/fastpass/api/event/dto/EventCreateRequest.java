package com.fastpass.api.event.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public record EventCreateRequest(
        @NotBlank String title,
        String description,
        @Min(1) int capacity,
        @NotNull LocalDateTime eventStartAt
) {
}
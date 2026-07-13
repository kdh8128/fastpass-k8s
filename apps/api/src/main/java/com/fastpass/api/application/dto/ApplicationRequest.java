package com.fastpass.api.application.dto;

import jakarta.validation.constraints.NotBlank;

public record ApplicationRequest(
        @NotBlank String applicantName
) {
}

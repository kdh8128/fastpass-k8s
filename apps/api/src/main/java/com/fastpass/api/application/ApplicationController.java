package com.fastpass.api.application;

import com.fastpass.api.application.dto.ApplicationRequest;
import com.fastpass.api.application.dto.ApplicationResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
public class ApplicationController {

    private final ApplicationService applicationService;

    public ApplicationController(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @PostMapping("/api/events/{eventId}/apply")
    public ApplicationResponse apply(
            @PathVariable Long eventId,
            @Valid @RequestBody ApplicationRequest request
    ) {
        return applicationService.apply(eventId, request);
    }

    @GetMapping("/api/applications/{applicationId}")
    public ApplicationResponse getApplication(@PathVariable Long applicationId) {
        return applicationService.getApplication(applicationId);
    }
}

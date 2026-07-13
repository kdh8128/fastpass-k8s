package com.fastpass.api.event;

import com.fastpass.api.event.dto.EventCreateRequest;
import com.fastpass.api.event.dto.EventResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping
    public EventResponse createEvent(@Valid @RequestBody EventCreateRequest request) {
        return eventService.createEvent(request);
    }

    @GetMapping
    public List<EventResponse> getEvents() {
        return eventService.getEvents();
    }

    @GetMapping("/{eventId}")
    public EventResponse getEvent(@PathVariable Long eventId) {
        return eventService.getEvent(eventId);
    }
}

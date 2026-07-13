package com.fastpass.api.event;

import com.fastpass.api.event.dto.EventCreateRequest;
import com.fastpass.api.event.dto.EventResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class EventService {

    private final EventRepository eventRepository;

    public EventService(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    @Transactional
    public EventResponse createEvent(EventCreateRequest request) {
        Event event = new Event(
                request.title(),
                request.description(),
                request.capacity(),
                request.eventStartAt()
        );

        Event savedEvent = eventRepository.save(event);
        return EventResponse.from(savedEvent);
    }

    @Transactional(readOnly = true)
    public List<EventResponse> getEvents() {
        return eventRepository.findAll()
                .stream()
                .map(EventResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public EventResponse getEvent(Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("Event not found. id=" + eventId));

        return EventResponse.from(event);
    }
}
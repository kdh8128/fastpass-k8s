package com.fastpass.api.event;

import com.fastpass.api.common.exception.NotFoundException;
import com.fastpass.api.event.dto.EventCreateRequest;
import com.fastpass.api.event.dto.EventResponse;
import com.fastpass.api.queue.ApplicationQueueService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

@Service
public class EventService {

    private final EventRepository eventRepository;
    private final ApplicationQueueService applicationQueueService;

    public EventService(
            EventRepository eventRepository,
            ApplicationQueueService applicationQueueService
    ) {
        this.eventRepository =
                eventRepository;

        this.applicationQueueService =
                applicationQueueService;
    }

    @Transactional
    public EventResponse createEvent(
            EventCreateRequest request
    ) {
        Event event = new Event(
                request.title(),
                request.description(),
                request.capacity(),
                request.eventStartAt()
        );

        Event savedEvent =
                eventRepository.save(event);

        Long eventId =
                savedEvent.getId();

        /*
         * PostgreSQL commit이 성공한 뒤에만
         * Redis에 이벤트 존재 키를 생성한다.
         */
        TransactionSynchronizationManager
                .registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                applicationQueueService
                                        .registerEvent(
                                                eventId
                                        );
                            }
                        }
                );

        return EventResponse.from(
                savedEvent
        );
    }

    @Transactional(readOnly = true)
    public List<EventResponse> getEvents() {
        return eventRepository.findAll()
                .stream()
                .map(EventResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public EventResponse getEvent(
            Long eventId
    ) {
        Event event =
                eventRepository
                        .findById(eventId)
                        .orElseThrow(() ->
                                new NotFoundException(
                                        "Event not found. id="
                                                + eventId
                                )
                        );

        return EventResponse.from(event);
    }
}
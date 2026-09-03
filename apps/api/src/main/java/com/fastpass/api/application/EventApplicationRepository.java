package com.fastpass.api.application;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface EventApplicationRepository
        extends JpaRepository<EventApplication, Long> {

    Optional<EventApplication> findByRequestId(
            Long requestId
    );

    boolean existsByEvent_IdAndApplicantName(
            Long eventId,
            String applicantName
    );

    @Query("""
            select coalesce(max(e.requestId), 0)
            from EventApplication e
            """)
    Long findMaxRequestId();

    @Query("""
            select coalesce(max(e.id), 0)
            from EventApplication e
            """)
    Long findMaxId();
}
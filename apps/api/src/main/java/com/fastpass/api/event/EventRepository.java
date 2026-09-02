package com.fastpass.api.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRepository extends JpaRepository<Event, Long> {

    @Modifying
    @Query("""
            update Event e
            set e.appliedCount = e.appliedCount + 1
            where e.id = :eventId
              and e.appliedCount < e.capacity
            """)
    int tryIncreaseAppliedCount(@Param("eventId") Long eventId);
}
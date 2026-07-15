package com.fastpass.api.application;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EventApplicationRepository extends JpaRepository<EventApplication, Long> {

    boolean existsByEvent_IdAndApplicantName(Long eventId, String applicantName);
}
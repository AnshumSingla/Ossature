package com.ossature.interviewer.repo;

import com.ossature.interviewer.domain.InterviewSession;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InterviewSessionRepository extends JpaRepository<InterviewSession, UUID> {
    List<InterviewSession> findByUserIdOrderByStartedAtDesc(UUID userId);
}

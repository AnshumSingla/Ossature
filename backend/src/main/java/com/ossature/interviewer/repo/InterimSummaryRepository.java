package com.ossature.interviewer.repo;

import com.ossature.interviewer.domain.InterimSummary;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InterimSummaryRepository extends JpaRepository<InterimSummary, UUID> {
    List<InterimSummary> findBySessionIdOrderByOrdinalAsc(UUID sessionId);

    long countBySessionId(UUID sessionId);
}

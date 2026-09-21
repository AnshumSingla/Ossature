package com.ossature.interviewer.repo;

import com.ossature.interviewer.domain.SessionEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionEventRepository extends JpaRepository<SessionEvent, Long> {

    List<SessionEvent> findBySessionIdAndSeqGreaterThanOrderBySeqAsc(UUID sessionId, long seq);

    List<SessionEvent> findBySessionIdOrderBySeqAsc(UUID sessionId);

    /** Highest seq assigned so far for a session, or null if none. */
    @org.springframework.data.jpa.repository.Query(
        "select max(e.seq) from SessionEvent e where e.sessionId = :sessionId")
    Long findMaxSeq(UUID sessionId);

    /** Chat turns since the last summary boundary, for the Summary node's window (FR9). */
    List<SessionEvent> findBySessionIdAndTypeAndTsAfterOrderByTsAsc(
            UUID sessionId, String type, Instant ts);

    /** Most recent diagram snapshot, for the Summary node's "diagram state" input. */
    Optional<SessionEvent> findFirstBySessionIdAndTypeOrderByTsDesc(UUID sessionId, String type);
}

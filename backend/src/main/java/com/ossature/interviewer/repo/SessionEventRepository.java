package com.ossature.interviewer.repo;

import org.springframework.transaction.annotation.Transactional;
import com.ossature.interviewer.domain.SessionEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/*
 * Every repository is read-only-transactional by default: Spring Data runs derived query methods
 * (findByEmail, findByState, ...) OUTSIDE any transaction otherwise, and row-level security needs a
 * transaction to attach the acting user to (see db/RlsJpaTransactionManager). Without one the query
 * would run with no identity and see nothing. Methods inherited from JpaRepository (save, ...) keep
 * their own read-write transactions.
 */
@Transactional(readOnly = true)
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

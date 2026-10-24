package com.ossature.interviewer.service;

import com.ossature.interviewer.logging.LogSafe;
import com.ossature.interviewer.agent.AgentClient;
import com.ossature.interviewer.agent.AgentDtos;
import com.ossature.interviewer.config.AppProperties;
import com.ossature.interviewer.db.DbContext;
import com.ossature.interviewer.domain.InterimSummary;
import com.ossature.interviewer.domain.InterviewSession;
import com.ossature.interviewer.domain.SessionEvent;
import com.ossature.interviewer.domain.SessionState;
import com.ossature.interviewer.repo.InterimSummaryRepository;
import com.ossature.interviewer.repo.InterviewSessionRepository;
import com.ossature.interviewer.repo.SessionEventRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Drives the Summary node on a fixed cadence for every active session (PRD 6.3 node 4 / FR9).
 *
 * <p>Ticks far more often than the cadence itself ({@code ossature.session.summary-interval},
 * ~6 min) so a missed tick or a slow restart doesn't skip a summary; each tick just checks
 * whether a session has crossed its next {@code interval}-sized boundary since it started.
 */
@Component
public class SummaryScheduler {

    private static final Logger log = LoggerFactory.getLogger(SummaryScheduler.class);
    private static final String CHAT_MESSAGE = "chat.message";
    private static final String DIAGRAM_SNAPSHOT = "diagram.snapshot";

    private final InterviewSessionRepository sessions;
    private final InterimSummaryRepository summaries;
    private final SessionEventRepository events;
    private final AgentClient agent;
    private final AppProperties props;
    private final ObjectMapper mapper;
    private final SchedulerLocks locks;

    public SummaryScheduler(InterviewSessionRepository sessions, InterimSummaryRepository summaries,
                            SessionEventRepository events, AgentClient agent, AppProperties props,
                            ObjectMapper mapper, SchedulerLocks locks) {
        this.sessions = sessions;
        this.summaries = summaries;
        this.events = events;
        this.agent = agent;
        this.props = props;
        this.mapper = mapper;
        this.locks = locks;
    }

    private static final String LOCK = "summary-sweep";
    /** Lease length: a sweep calls a model per due session, but a crashed holder must not block it for long. */
    private static final Duration LOCK_AT_MOST = Duration.ofMinutes(5);

    @Scheduled(fixedDelay = 30_000)
    public void tick() {
        // With several backend instances every one of them runs this schedule; only one sweeps at a time.
        if (!locks.tryAcquire(LOCK, LOCK_AT_MOST)) {
            log.debug("Summary sweep skipped: another instance holds the lease");
            return;
        }
        try {
            sweep();
        } finally {
            locks.release(LOCK);
        }
    }

    private void sweep() {
        long intervalMs = props.session().summaryInterval().toMillis();
        // 'scheduler' scope may only LIST active sessions (to learn who owns them). The real work then
        // runs as each session's owner, with exactly that user's access and no more.
        List<InterviewSession> active =
                DbContext.callInScope(DbContext.SCOPE_SCHEDULER, () -> sessions.findByState(SessionState.ACTIVE));
        for (InterviewSession session : active) {
            try {
                DbContext.runAs(session.getUserId(), false, () -> maybeSummarize(session, intervalMs));
            } catch (RuntimeException e) {
                LogSafe.warn(log, "Summary tick failed for session {}", e, session.getId());
            }
        }
    }

    private void maybeSummarize(InterviewSession session, long intervalMs) {
        UUID sessionId = session.getId();
        long elapsedMs = Instant.now().toEpochMilli() - session.getStartedAt().toEpochMilli();
        int dueOrdinal = (int) (elapsedMs / intervalMs);
        if (dueOrdinal < 1) {
            return;
        }
        long have = summaries.countBySessionId(sessionId);
        if (have >= dueOrdinal) {
            return; // already caught up
        }
        int nextOrdinal = (int) have + 1;

        Instant windowStart = windowStart(sessionId, have);
        String transcriptWindow = transcriptWindow(sessionId, windowStart);
        Map<String, Object> diagramState = latestDiagramState(sessionId);

        var response = agent.requestSummary(sessionId.toString(),
                new AgentDtos.SummaryRequest(sessionId.toString(), nextOrdinal, transcriptWindow, diagramState));
        if (response == null || response.content() == null || response.content().isBlank()) {
            return;
        }
        persist(sessionId, nextOrdinal, response.content());
    }

    private Instant windowStart(UUID sessionId, long haveSummaries) {
        if (haveSummaries == 0) {
            return sessions.findById(sessionId).map(InterviewSession::getStartedAt).orElse(Instant.EPOCH);
        }
        List<InterimSummary> ordered = summaries.findBySessionIdOrderByOrdinalAsc(sessionId);
        return ordered.isEmpty() ? Instant.EPOCH : ordered.get(ordered.size() - 1).getCreatedAt();
    }

    private String transcriptWindow(UUID sessionId, Instant afterTs) {
        List<SessionEvent> chatEvents =
                events.findBySessionIdAndTypeAndTsAfterOrderByTsAsc(sessionId, CHAT_MESSAGE, afterTs);
        StringBuilder sb = new StringBuilder();
        for (SessionEvent event : chatEvents) {
            Map<String, Object> payload = readMap(event.getPayload());
            sb.append(payload.getOrDefault("sender", "?"))
              .append(": ")
              .append(payload.getOrDefault("text", ""))
              .append('\n');
        }
        return sb.toString();
    }

    private Map<String, Object> latestDiagramState(UUID sessionId) {
        return events.findFirstBySessionIdAndTypeOrderByTsDesc(sessionId, DIAGRAM_SNAPSHOT)
                .map(e -> readMap(e.getPayload()))
                .orElse(Map.of());
    }

    private void persist(UUID sessionId, int ordinal, String content) {
        summaries.save(new InterimSummary(sessionId, ordinal, content));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, Map.class);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }
}

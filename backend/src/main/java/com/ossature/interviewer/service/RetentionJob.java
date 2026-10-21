package com.ossature.interviewer.service;

import com.ossature.interviewer.config.RetentionProperties;
import com.ossature.interviewer.db.DbContext;
import com.ossature.interviewer.domain.InterviewSession;
import com.ossature.interviewer.repo.InterviewSessionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes interviews (transcript, diagram, summaries, debrief) once they are older than the configured
 * retention period (H21). Runs daily on one backend instance at a time. It acts in the {@code retention}
 * database scope, which can only ever see or delete sessions older than 30 days - a floor enforced by the
 * database itself, so even a wrong setting here cannot touch recent interviews.
 */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);
    private static final String LOCK = "retention-purge";

    private final InterviewSessionRepository sessions;
    private final SessionService sessionService;
    private final RetentionProperties props;
    private final SchedulerLocks locks;

    public RetentionJob(InterviewSessionRepository sessions, SessionService sessionService, RetentionProperties props,
                        SchedulerLocks locks) {
        this.sessions = sessions;
        this.sessionService = sessionService;
        this.props = props;
        this.locks = locks;
    }

    @Scheduled(cron = "${ossature.retention.cron:0 30 3 * * *}")
    public void run() {
        if (props.sessionDays() == 0 || !locks.tryAcquire(LOCK, Duration.ofMinutes(30))) {
            return;
        }
        try {
            purge(Instant.now().minus(Duration.ofDays(props.sessionDays())));
        } finally {
            locks.release(LOCK);
        }
    }

    /** Deletes sessions that started before {@code cutoff}; returns how many. */
    public int purge(Instant cutoff) {
        List<InterviewSession> old = DbContext.callInScope(DbContext.SCOPE_RETENTION, () -> sessions.findByStartedAtBefore(cutoff));
        int deleted = 0;
        for (InterviewSession s : old) {
            try {
                DbContext.callInScope(DbContext.SCOPE_RETENTION, () -> {
                    sessions.deleteById(s.getId()); // events and summaries cascade
                    return null;
                });
                sessionService.forgetAgentState(s.getId());
                deleted++;
            } catch (RuntimeException e) {
                com.ossature.interviewer.logging.LogSafe.warn(log, "Retention purge failed for session {}", e, s.getId());
            }
        }
        if (deleted > 0) {
            log.info("Retention: deleted {} interview(s) older than {} days", deleted, props.sessionDays());
        }
        return deleted;
    }
}

package com.ossature.interviewer.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ossature.interviewer.agent.AgentClient;
import com.ossature.interviewer.repo.InterimSummaryRepository;
import com.ossature.interviewer.repo.InterviewSessionRepository;
import com.ossature.interviewer.repo.SessionEventRepository;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** A1.6: with several backend instances, only the one holding the lease sweeps - and it always lets go. */
class SummarySchedulerLeaseTest {

    private final InterviewSessionRepository sessions = mock(InterviewSessionRepository.class);
    private final SchedulerLocks locks = mock(SchedulerLocks.class);
    private final SummaryScheduler scheduler = new SummaryScheduler(sessions, mock(InterimSummaryRepository.class),
            mock(SessionEventRepository.class), mock(AgentClient.class), props(), JsonMapper.builder().build(), locks);

    private static com.ossature.interviewer.config.AppProperties props() {
        return new com.ossature.interviewer.config.AppProperties(null,
                new com.ossature.interviewer.config.AppProperties.Session(null, Duration.ofMinutes(6)), null, null, null, null);
    }

    @Test
    void aSecondInstanceDoesNothingWhileAnotherHoldsTheLease() {
        when(locks.tryAcquire(eq("summary-sweep"), any())).thenReturn(false);
        scheduler.tick();
        verify(sessions, never()).findByState(any());
        verify(locks, never()).release(any());
    }

    @Test
    void theHolderSweepsAndThenReleases() {
        when(locks.tryAcquire(eq("summary-sweep"), any())).thenReturn(true);
        when(sessions.findByState(any())).thenReturn(List.of());
        scheduler.tick();
        verify(sessions).findByState(any());
        verify(locks).release("summary-sweep");
    }

    @Test
    void theLeaseIsReleasedEvenWhenTheSweepFails() {
        when(locks.tryAcquire(eq("summary-sweep"), any())).thenReturn(true);
        when(sessions.findByState(any())).thenThrow(new IllegalStateException("db down"));
        assertThatThrownBy(scheduler::tick).isInstanceOf(IllegalStateException.class);
        verify(locks).release("summary-sweep");
    }
}

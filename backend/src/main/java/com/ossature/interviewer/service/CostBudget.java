package com.ossature.interviewer.service;

import com.ossature.interviewer.config.BudgetProperties;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A per-person daily budget and a service-wide hourly circuit breaker for paid model calls (C6). Counted through the
 * shared {@link RateLimiter}, so the limits hold across every backend instance.
 */
@Component
public class CostBudget {

    public enum Verdict { OK, USER_LIMIT, GLOBAL_LIMIT }

    private final RateLimiter limiter;
    private final BudgetProperties props;

    public CostBudget(RateLimiter limiter, BudgetProperties props) {
        this.limiter = limiter;
        this.props = props;
    }

    /** Spends one turn for {@code userId}, if both budgets allow it. */
    public Verdict spend(UUID userId) {
        // The person's own budget first: a runaway user must not use up the service-wide allowance.
        if (props.userDailyTurns() > 0
                && !limiter.allow("llm:user:" + userId, props.userDailyTurns(), Duration.ofHours(24).toMillis())) {
            return Verdict.USER_LIMIT;
        }
        if (props.globalHourlyTurns() > 0
                && !limiter.allow("llm:global", props.globalHourlyTurns(), Duration.ofHours(1).toMillis())) {
            return Verdict.GLOBAL_LIMIT;
        }
        return Verdict.OK;
    }
}

package com.ossature.interviewer.web;

import com.ossature.interviewer.config.AppProperties;
import com.ossature.interviewer.security.WsTicketService;
import com.ossature.interviewer.web.dto.ApiDtos.WsTicketView;
import com.ossature.interviewer.domain.InterviewSession;
import com.ossature.interviewer.repo.InterimSummaryRepository;
import com.ossature.interviewer.service.InterviewFlowService;
import com.ossature.interviewer.service.RateLimiter;
import com.ossature.interviewer.service.SessionService;
import com.ossature.interviewer.service.TooManyRequestsException;
import java.util.Map;
import com.ossature.interviewer.web.dto.ApiDtos.CreateSessionRequest;
import com.ossature.interviewer.web.dto.ApiDtos.SessionView;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessions;
    private final InterimSummaryRepository summaries;
    private final InterviewFlowService flow;
    private final RateLimiter rateLimiter;
    private final AppProperties.RateLimit limits;
    private final WsTicketService tickets;

    public SessionController(SessionService sessions, InterimSummaryRepository summaries,
                             InterviewFlowService flow, RateLimiter rateLimiter, AppProperties props,
                             WsTicketService tickets) {
        this.tickets = tickets;
        this.sessions = sessions;
        this.summaries = summaries;
        this.flow = flow;
        this.rateLimiter = rateLimiter;
        this.limits = props.rateLimit();
    }

    /** Capped per user - each creation kicks off a real, possibly paid, Council LLM call. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionView create(@Valid @RequestBody CreateSessionRequest req, Authentication auth) {
        UUID caller = userId(auth);
        if (!rateLimiter.allow("session-create:" + caller, limits.sessionCreateMax(),
                limits.sessionCreateWindow().toMillis())) {
            throw new TooManyRequestsException("Too many sessions created recently - try again later");
        }
        InterviewSession s = sessions.create(caller, req.questionId());
        return SessionView.of(s);
    }

    /** Only the caller's own sessions - userId comes from the token, never a query param. */
    @GetMapping
    public List<SessionView> list(Authentication auth) {
        return sessions.listForUser(userId(auth)).stream().map(SessionView::of).toList();
    }

    @GetMapping("/{id}")
    public SessionView get(@PathVariable UUID id, Authentication auth) {
        return SessionView.of(owned(id, auth));
    }

    /** Final debrief JSON, available once {@code session.end} has been processed. */
    @GetMapping("/{id}/debrief")
    public String debrief(@PathVariable UUID id, Authentication auth) {
        return owned(id, auth).getDebrief();
    }

    @GetMapping("/{id}/summaries")
    public List<String> interimSummaries(@PathVariable UUID id, Authentication auth) {
        owned(id, auth);
        return summaries.findBySessionIdOrderByOrdinalAsc(id).stream()
                .map(s -> s.getContent()).toList();
    }

    /** Final diagram JSON (nodes/edges), set once {@code diagram.locked} has been processed. */
    @GetMapping("/{id}/diagram")
    public String diagram(@PathVariable UUID id, Authentication auth) {
        return owned(id, auth).getFinalDiagram();
    }

    /**
     * Full transcript/event replay for a past session (history UI) - same frame shape the
     * live WebSocket uses for reconnect replay, so the frontend can reuse one renderer for
     * both (PRD Phase 4 "Session history / replay UI").
     */
    @GetMapping("/{id}/events")
    public List<Map<String, Object>> events(@PathVariable UUID id, Authentication auth) {
        owned(id, auth);
        return flow.fullHistory(id);
    }

    /**
     * A one-time ticket for opening this session's WebSocket. Replaces putting the login token in
     * the WebSocket URL: the ticket is short-lived, single-use and bound to this session.
     */
    @PostMapping("/{id}/ws-ticket")
    public WsTicketView wsTicket(@PathVariable UUID id, Authentication auth) {
        UUID caller = userId(auth);
        InterviewSession s = owned(id, auth);
        if (!rateLimiter.allow("ws-ticket:" + caller, 60, 60_000)) {
            throw new TooManyRequestsException("Too many connection attempts - try again shortly");
        }
        return new WsTicketView(tickets.issue(caller, s.getId()), tickets.ttl().toSeconds());
    }

    private InterviewSession owned(UUID sessionId, Authentication auth) {
        InterviewSession s = sessions.get(sessionId);
        if (!s.getUserId().equals(userId(auth))) {
            throw new AccessDeniedException("Not your session");
        }
        return s;
    }

    private static UUID userId(Authentication auth) {
        return (UUID) auth.getPrincipal();
    }
}

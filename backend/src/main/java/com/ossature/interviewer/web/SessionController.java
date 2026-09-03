package com.ossature.interviewer.web;

import com.ossature.interviewer.domain.InterviewSession;
import com.ossature.interviewer.repo.InterimSummaryRepository;
import com.ossature.interviewer.service.SessionService;
import com.ossature.interviewer.web.dto.ApiDtos.CreateSessionRequest;
import com.ossature.interviewer.web.dto.ApiDtos.SessionView;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessions;
    private final InterimSummaryRepository summaries;

    public SessionController(SessionService sessions, InterimSummaryRepository summaries) {
        this.sessions = sessions;
        this.summaries = summaries;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionView create(@Valid @RequestBody CreateSessionRequest req) {
        InterviewSession s = sessions.create(req.userId(), req.questionId());
        return SessionView.of(s);
    }

    @GetMapping
    public List<SessionView> list(@RequestParam UUID userId) {
        return sessions.listForUser(userId).stream().map(SessionView::of).toList();
    }

    @GetMapping("/{id}")
    public SessionView get(@PathVariable UUID id) {
        return SessionView.of(sessions.get(id));
    }

    /** Final debrief JSON, available once {@code session.end} has been processed. */
    @GetMapping("/{id}/debrief")
    public String debrief(@PathVariable UUID id) {
        return sessions.get(id).getDebrief();
    }

    @GetMapping("/{id}/summaries")
    public List<String> interimSummaries(@PathVariable UUID id) {
        return summaries.findBySessionIdOrderByOrdinalAsc(id).stream()
                .map(s -> s.getContent()).toList();
    }
}

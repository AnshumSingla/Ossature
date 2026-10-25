package com.ossature.interviewer.web;

import com.ossature.interviewer.domain.Question;
import com.ossature.interviewer.domain.QuestionStatus;
import com.ossature.interviewer.service.QuestionBankService;
import com.ossature.interviewer.web.dto.ApiDtos.QuestionView;
import com.ossature.interviewer.web.dto.ApiDtos.SubmitQuestionRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/questions")
public class QuestionController {

    private final QuestionBankService bank;

    public QuestionController(QuestionBankService bank) {
        this.bank = bank;
    }

    @GetMapping
    public List<QuestionView> list() {
        return bank.listApproved().stream().map(QuestionView::of).toList();
    }

    /** Owner-only (PRD 9 review workflow) - enforced by {@code SecurityConfig}. */
    @GetMapping("/pending")
    public List<QuestionView> pending() {
        return bank.listPending().stream().map(QuestionView::of).toList();
    }

    /**
     * Public for approved questions only. A pending/rejected one is visible just to its
     * submitter and the owner; everyone else gets the same 404 as for an unknown id, so the
     * endpoint can't be used to read unreviewed content or to probe which ids exist.
     */
    @GetMapping("/{id}")
    public QuestionView get(@PathVariable UUID id, Authentication auth) {
        Question q = bank.get(id);
        if (q.getStatus() != QuestionStatus.APPROVED && !canSeeUnapproved(q, auth)) {
            throw new IllegalArgumentException("Unknown question " + id);
        }
        return QuestionView.of(q);
    }

    private static boolean canSeeUnapproved(Question q, Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        boolean owner = auth.getAuthorities().stream().anyMatch(a -> "ROLE_OWNER".equals(a.getAuthority()));
        boolean submitter = auth.getPrincipal() instanceof UUID uid && uid.equals(q.getSubmittedBy());
        return owner || submitter;
    }

    /** Community submission -> PENDING, added only after manual owner review (PRD 9). */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public QuestionView submit(@Valid @RequestBody SubmitQuestionRequest req, Authentication auth) {
        UUID submittedBy = (UUID) auth.getPrincipal();
        return QuestionView.of(bank.submitForReview(req.title(), req.prompt(), submittedBy));
    }

    /** Owner-only. */
    @PostMapping("/{id}/approve")
    public QuestionView approve(@PathVariable UUID id) {
        return QuestionView.of(bank.approve(id));
    }

    /** Owner-only. */
    @PostMapping("/{id}/reject")
    public QuestionView reject(@PathVariable UUID id) {
        return QuestionView.of(bank.reject(id));
    }
}

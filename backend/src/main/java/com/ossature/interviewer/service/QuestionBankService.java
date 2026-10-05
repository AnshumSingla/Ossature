package com.ossature.interviewer.service;

import com.ossature.interviewer.domain.Question;
import com.ossature.interviewer.domain.QuestionStatus;
import com.ossature.interviewer.repo.QuestionRepository;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Question bank reads plus the community-submission entry point (PRD 9 / FR11). */
@Service
public class QuestionBankService {

    private static final Pattern NON_SLUG_CHARS = Pattern.compile("[^a-z0-9]+");

    private final QuestionRepository questions;

    public QuestionBankService(QuestionRepository questions) {
        this.questions = questions;
    }

    @Transactional(readOnly = true)
    public List<Question> listApproved() {
        return questions.findByStatus(QuestionStatus.APPROVED);
    }

    @Transactional(readOnly = true)
    public List<Question> listPending() {
        return questions.findByStatus(QuestionStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public Question get(UUID id) {
        return questions.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown question " + id));
    }

    /**
     * Community submissions land as PENDING and are added to the bank only after the
     * project owner manually reviews them (PRD 9, "Resolved: manual review").
     */
    @Transactional
    public Question submitForReview(String title, String prompt, UUID submittedBy) {
        return questions.save(new Question(uniqueSlug(title), title, prompt, submittedBy));
    }

    @Transactional
    public Question approve(UUID id) {
        Question q = get(id);
        q.approve();
        return questions.save(q);
    }

    @Transactional
    public Question reject(UUID id) {
        Question q = get(id);
        q.reject();
        return questions.save(q);
    }

    /** Slugifies the title, appending a short suffix on collision instead of failing. */
    private String uniqueSlug(String title) {
        String base = NON_SLUG_CHARS.matcher(title.toLowerCase()).replaceAll("-").replaceAll("(^-+|-+$)", "");
        if (base.isBlank()) {
            base = "question";
        }
        String slug = base;
        int suffix = 2;
        while (questions.findBySlug(slug).isPresent()) {
            slug = base + "-" + suffix++;
        }
        return slug;
    }
}

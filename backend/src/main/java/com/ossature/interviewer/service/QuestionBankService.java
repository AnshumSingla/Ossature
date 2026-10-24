package com.ossature.interviewer.service;

import com.ossature.interviewer.domain.Question;
import com.ossature.interviewer.domain.QuestionStatus;
import com.ossature.interviewer.repo.QuestionRepository;
import com.ossature.interviewer.repo.UserRepository;
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
    private final UserRepository users;

    public QuestionBankService(QuestionRepository questions, UserRepository users) {
        this.questions = questions;
        this.users = users;
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
        if (!users.existsById(submittedBy)) {
            throw new AccountGoneException();
        }
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
        // Row-level security hides other people's pending submissions, so a collision with one cannot be
        // detected here and would fail on the unique index. A short random suffix makes that practically
        // impossible, so submissions never depend on seeing rows they aren't allowed to see.
        String suffix = Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong(0x1000000L, 0xFFFFFFFFL));
        String base = NON_SLUG_CHARS.matcher(title.toLowerCase()).replaceAll("-").replaceAll("(^-+|-+$)", "");
        if (base.isBlank()) {
            base = "question";
        }
        String slug = base + "-" + suffix;
        int n = 2;
        while (questions.findBySlug(slug).isPresent()) {
            slug = base + "-" + suffix + "-" + n++;
        }
        return slug;
    }
}

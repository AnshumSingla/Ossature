package com.ossature.interviewer.service;

import com.ossature.interviewer.domain.Question;
import com.ossature.interviewer.domain.QuestionStatus;
import com.ossature.interviewer.repo.QuestionRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Question bank reads plus the community-submission entry point (PRD 9 / FR11). */
@Service
public class QuestionBankService {

    private final QuestionRepository questions;

    public QuestionBankService(QuestionRepository questions) {
        this.questions = questions;
    }

    @Transactional(readOnly = true)
    public List<Question> listApproved() {
        return questions.findByStatus(QuestionStatus.APPROVED);
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
        // Persisted via a small factory kept out of the entity's public API.
        throw new UnsupportedOperationException(
                "Wire up Question creation for community submissions (PRD Phase 4).");
    }
}

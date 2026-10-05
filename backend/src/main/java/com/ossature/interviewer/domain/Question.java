package com.ossature.interviewer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A system-design prompt. When {@link #referenceSolution} is non-null the prompt carries a
 * vetted reference and the Council node is skipped for sessions using it (PRD 6.3 / FR4).
 */
@Entity
@Table(name = "questions")
public class Question {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String prompt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QuestionSource source = QuestionSource.SEED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QuestionStatus status = QuestionStatus.APPROVED;

    /** JSON: { summary, diagram: { nodes, edges }, tradeoffs: [...] }. Null until vetted. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reference_solution", columnDefinition = "jsonb")
    private String referenceSolution;

    @Column(name = "submitted_by")
    private UUID submittedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Question() {}

    /** Community submission - lands PENDING, only visible in the bank once approved. */
    public Question(String slug, String title, String prompt, UUID submittedBy) {
        this.slug = slug;
        this.title = title;
        this.prompt = prompt;
        this.source = QuestionSource.COMMUNITY;
        this.status = QuestionStatus.PENDING;
        this.submittedBy = submittedBy;
    }

    public void approve() {
        this.status = QuestionStatus.APPROVED;
        this.updatedAt = Instant.now();
    }

    public void reject() {
        this.status = QuestionStatus.REJECTED;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }

    public String getTitle() {
        return title;
    }

    public String getPrompt() {
        return prompt;
    }

    public QuestionSource getSource() {
        return source;
    }

    public QuestionStatus getStatus() {
        return status;
    }

    public String getReferenceSolution() {
        return referenceSolution;
    }

    public boolean hasVettedReference() {
        return referenceSolution != null && !referenceSolution.isBlank();
    }

    public UUID getSubmittedBy() {
        return submittedBy;
    }
}

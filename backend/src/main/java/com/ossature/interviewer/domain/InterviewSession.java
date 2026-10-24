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

/** One interview run. Owns diagram-lock state and the reference solution actually used. */
@Entity
@Table(name = "interview_sessions")
public class InterviewSession {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "question_id")
    private UUID questionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SessionState state = SessionState.ACTIVE;

    @Column(name = "diagram_locked", nullable = false)
    private boolean diagramLocked = false;

    /** Copied from the bank entry, or filled in by the Council node once it finishes. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reference_solution", columnDefinition = "jsonb")
    private String referenceSolution;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "final_diagram", columnDefinition = "jsonb")
    private String finalDiagram;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String debrief;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    protected InterviewSession() {}

    public InterviewSession(UUID userId, UUID questionId) {
        this.userId = userId;
        this.questionId = questionId;
    }

    public void attachReferenceSolution(String json) {
        this.referenceSolution = json;
    }

    public void submit(String finalDiagramJson) {
        this.finalDiagram = finalDiagramJson;
        this.diagramLocked = true;
        this.state = SessionState.SUBMITTED;
        this.submittedAt = Instant.now();
    }

    public void end(String debriefJson) {
        this.debrief = debriefJson;
        this.state = SessionState.ENDED;
        this.endedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getQuestionId() {
        return questionId;
    }

    public SessionState getState() {
        return state;
    }

    public boolean isDiagramLocked() {
        return diagramLocked;
    }

    public String getReferenceSolution() {
        return referenceSolution;
    }

    public String getFinalDiagram() {
        return finalDiagram;
    }

    public String getDebrief() {
        return debrief;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }
}

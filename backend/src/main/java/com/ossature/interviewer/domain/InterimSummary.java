package com.ossature.interviewer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Rolling interim summary produced by the Summary node (~every 6 min; PRD 6.3 / FR9). */
@Entity
@Table(name = "interim_summaries")
public class InterimSummary {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(nullable = false)
    private int ordinal;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected InterimSummary() {}

    public InterimSummary(UUID sessionId, int ordinal, String content) {
        this.sessionId = sessionId;
        this.ordinal = ordinal;
        this.content = content;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public int getOrdinal() {
        return ordinal;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

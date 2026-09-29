package com.ossature.interviewer.agent;

import com.ossature.interviewer.agent.AgentDtos.DebriefRequest;
import com.ossature.interviewer.agent.AgentDtos.DebriefResponse;
import com.ossature.interviewer.agent.AgentDtos.ReviewResponse;
import com.ossature.interviewer.agent.AgentDtos.StartRequest;
import com.ossature.interviewer.agent.AgentDtos.SubmitRequest;
import com.ossature.interviewer.agent.AgentDtos.SummaryRequest;
import com.ossature.interviewer.agent.AgentDtos.SummaryResponse;
import com.ossature.interviewer.agent.AgentDtos.TranscriptRequest;
import com.ossature.interviewer.agent.AgentDtos.TurnResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Thin HTTP client for the Python LangGraph service. Spring Boot owns sessions/persistence;
 * this bridge forwards transcript chunks, the submitted diagram and timer triggers, and
 * returns the resulting AI content for the backend to push over WebSocket (PRD 6.2).
 */
@Component
public class AgentClient {

    private final RestClient http;

    public AgentClient(RestClient agentRestClient) {
        this.http = agentRestClient;
    }

    public TurnResponse startSession(StartRequest req) {
        return http.post().uri("/agent/sessions/{id}/start", req.sessionId())
                .body(req).retrieve().body(TurnResponse.class);
    }

    /** Feed a finalized transcript segment; may return an AI turn (clarifying/probing). */
    public TurnResponse sendTranscript(String sessionId, TranscriptRequest req) {
        return http.post().uri("/agent/sessions/{id}/transcript", sessionId)
                .body(req).retrieve().body(TurnResponse.class);
    }

    public ReviewResponse submitDiagram(String sessionId, SubmitRequest req) {
        return http.post().uri("/agent/sessions/{id}/submit", sessionId)
                .body(req).retrieve().body(ReviewResponse.class);
    }

    public SummaryResponse requestSummary(String sessionId, SummaryRequest req) {
        return http.post().uri("/agent/sessions/{id}/summary", sessionId)
                .body(req).retrieve().body(SummaryResponse.class);
    }

    public DebriefResponse requestDebrief(String sessionId, DebriefRequest req) {
        return http.post().uri("/agent/sessions/{id}/debrief", sessionId)
                .body(req).retrieve().body(DebriefResponse.class);
    }

    /**
     * Purges the agent's working copy of session state once the backend holds the debrief
     * durably - the agent's copy exists only to survive its own restarts mid-session, not
     * as a second system of record (see agent/app/store.py).
     */
    public void endSession(String sessionId) {
        http.delete().uri("/agent/sessions/{id}", sessionId).retrieve().toBodilessEntity();
    }
}

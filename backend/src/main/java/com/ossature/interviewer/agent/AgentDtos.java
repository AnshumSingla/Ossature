package com.ossature.interviewer.agent;

import java.util.List;
import java.util.Map;

/** Request/response payloads exchanged with the Python LangGraph service (PRD 6.1). */
public final class AgentDtos {

    private AgentDtos() {}

    public record StartRequest(String sessionId, String prompt, boolean hasVettedReference,
                               Map<String, Object> referenceSolution) {}

    /** An AI turn to relay to the candidate, plus whether the Council node is now running. */
    public record TurnResponse(String text, String kind, boolean councilRunning) {}

    public record TranscriptRequest(String sessionId, String text) {}

    public record ClarifyingRelay(String sessionId, String candidateQuestion, String aiAnswer) {}

    public record SubmitRequest(String sessionId, Map<String, Object> finalDiagram) {}

    public record Flaw(String summary, String questionPrompt, int priority) {}

    public record ReviewResponse(List<Flaw> flaws) {}

    public record SummaryRequest(String sessionId, int ordinal, String transcriptWindow,
                                 Map<String, Object> diagramState) {}

    public record SummaryResponse(int ordinal, String content) {}

    public record DebriefRequest(String sessionId, List<String> interimSummaries,
                                 Map<String, Object> referenceSolution) {}

    public record DebriefResponse(List<String> strengths, List<String> gaps,
                                  List<String> unresolved, String referenceComparison) {}
}

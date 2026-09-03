package com.ossature.interviewer.ws.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

/**
 * Typed view of the WebSocket protocol in PRD 6.4. One JSON object per frame; the {@code type}
 * discriminator routes it. {@code sessionId} and {@code ts} (epoch ms) are on every message.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes({
    @JsonSubTypes.Type(value = InterviewMessage.TranscriptPartial.class, name = "transcript.partial"),
    @JsonSubTypes.Type(value = InterviewMessage.TranscriptFinal.class, name = "transcript.final"),
    @JsonSubTypes.Type(value = InterviewMessage.DiagramSnapshot.class, name = "diagram.snapshot"),
    @JsonSubTypes.Type(value = InterviewMessage.DiagramLocked.class, name = "diagram.locked"),
    @JsonSubTypes.Type(value = InterviewMessage.ChatMessage.class, name = "chat.message"),
    @JsonSubTypes.Type(value = InterviewMessage.AiThinking.class, name = "ai.thinking"),
    @JsonSubTypes.Type(value = InterviewMessage.SessionEnd.class, name = "session.end"),
    @JsonSubTypes.Type(value = InterviewMessage.DebriefReady.class, name = "debrief.ready"),
})
public sealed interface InterviewMessage {

    String type();

    String sessionId();

    long ts();

    // --- candidate -> server ---

    record TranscriptPartial(String type, String sessionId, String text, long ts) implements InterviewMessage {}

    record TranscriptFinal(String type, String sessionId, String text, long ts) implements InterviewMessage {}

    record DiagramSnapshot(String type, String sessionId, List<Object> nodes, List<Object> edges, long ts)
            implements InterviewMessage {}

    record DiagramLocked(String type, String sessionId, Diagram finalDiagram, long ts) implements InterviewMessage {}

    record SessionEnd(String type, String sessionId, long ts) implements InterviewMessage {}

    // --- either direction ---

    /** {@code kind} is present on "ai" messages only: clarifying | probing | filler | flaw. */
    record ChatMessage(String type, String sessionId, String sender, String text, String kind, long ts)
            implements InterviewMessage {}

    // --- server -> candidate ---

    /** {@code node} is "council" | "review". */
    record AiThinking(String type, String sessionId, String node, long ts) implements InterviewMessage {}

    record DebriefReady(
            String type,
            String sessionId,
            List<String> strengths,
            List<String> gaps,
            List<String> unresolved,
            String referenceComparison,
            long ts) implements InterviewMessage {}

    record Diagram(List<Object> nodes, List<Object> edges) {}
}

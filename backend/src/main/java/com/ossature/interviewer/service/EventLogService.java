package com.ossature.interviewer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ossature.interviewer.domain.SessionEvent;
import com.ossature.interviewer.repo.SessionEventRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Append-only replay log with a monotonic per-session sequence (PRD 6.2, FR12). */
@Service
public class EventLogService {

    private final SessionEventRepository events;
    private final ObjectMapper mapper;

    public EventLogService(SessionEventRepository events, ObjectMapper mapper) {
        this.events = events;
        this.mapper = mapper;
    }

    @Transactional
    public SessionEvent append(UUID sessionId, String type, Object payload) {
        Long max = events.findMaxSeq(sessionId);
        long nextSeq = (max == null ? 0L : max) + 1;
        return events.save(new SessionEvent(sessionId, nextSeq, type, toJson(payload)));
    }

    /** Events after {@code afterSeq}, for reconnect/resume. Pass 0 for the full log. */
    @Transactional(readOnly = true)
    public List<SessionEvent> since(UUID sessionId, long afterSeq) {
        return events.findBySessionIdAndSeqGreaterThanOrderBySeqAsc(sessionId, afterSeq);
    }

    private String toJson(Object payload) {
        if (payload instanceof String s) {
            return s;
        }
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize event payload", e);
        }
    }
}

import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, type DebriefPayload, type QuestionView, type SessionView } from '../api/rest';
import type { AiTurnKind, Diagram } from '../types/protocol';
import type { ChatEntry } from '../hooks/useInterviewSocket';
import { TranscriptPanel } from '../features/interview/TranscriptPanel';
import { DebriefPanel } from '../features/debrief/DebriefPanel';
import { DiagramReadonly } from '../features/whiteboard/DiagramReadonly';

const EMPTY_DIAGRAM: Diagram = { nodes: [], edges: [] };

export function SessionReplayPage() {
  const { id } = useParams<{ id: string }>();
  const [session, setSession] = useState<SessionView | null>(null);
  const [question, setQuestion] = useState<QuestionView | null>(null);
  const [chat, setChat] = useState<ChatEntry[]>([]);
  const [diagram, setDiagram] = useState<Diagram>(EMPTY_DIAGRAM);
  const [debrief, setDebrief] = useState<DebriefPayload | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!id) return;
    api
      .getSession(id)
      .then((s) => {
        setSession(s);
        return api.getQuestion(s.questionId);
      })
      .then(setQuestion)
      .catch(() => setError('Could not load this session.'));

    api
      .getSessionEvents(id)
      .then((events) => {
        const turns: ChatEntry[] = [];
        for (const e of events) {
          if (e.type !== 'chat.message') continue;
          const p = e.payload as { sender?: 'candidate' | 'ai'; text?: string; kind?: AiTurnKind };
          if (p.text) turns.push({ sender: p.sender ?? 'ai', text: p.text, kind: p.kind, ts: e.ts });
        }
        setChat(turns);
      })
      .catch(() => setError('Could not load the transcript.'));

    api.getSessionDiagram(id).then((d) => setDiagram(d ?? EMPTY_DIAGRAM));
    api.getDebrief(id).then(setDebrief);
  }, [id]);

  if (!id) return <p>Missing session id.</p>;

  return (
    <div className="interview-layout">
      <div style={{ minWidth: 0 }}>
        <DiagramReadonly diagram={diagram} />
      </div>
      <div className="interview-sidebar">
        <div className="interview-toolbar">
          <div className="session-meta">
            <Link to="/history">&larr; History</Link> · Session {id.slice(0, 8)} · replay
            {session && ` · ${session.state}`}
          </div>
          <p className="prompt">{question?.prompt ?? (error && <span className="error-text">{error}</span>)}</p>
        </div>
        <div style={{ flex: 1, minHeight: 0, overflowY: 'auto' }}>
          {debrief && <DebriefPanel debrief={{ ...debrief, type: 'debrief.ready', sessionId: id, ts: 0 }} />}
          <TranscriptPanel chat={chat} partial="" thinkingNode={null} />
        </div>
      </div>
    </div>
  );
}

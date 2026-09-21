import { useCallback, useEffect, useRef, useState } from 'react';
import { InterviewSocket } from '../api/socket';
import type {
  AiTurnKind,
  ClientMessage,
  Diagram,
  ReplayMessage,
  ServerMessage,
} from '../types/protocol';

export interface ChatEntry {
  sender: 'candidate' | 'ai';
  text: string;
  kind?: AiTurnKind;
  ts: number;
}

export interface UseInterviewSocket {
  status: 'connecting' | 'open' | 'closed';
  chat: ChatEntry[];
  thinkingNode: 'council' | 'review' | null;
  debrief: Extract<ServerMessage, { type: 'debrief.ready' }> | null;
  sendPartial: (text: string) => void;
  sendFinal: (text: string) => void;
  sendSnapshot: (diagram: Diagram) => void;
  submitDiagram: (diagram: Diagram) => void;
  endSession: () => void;
}

type DebriefMsg = Extract<ServerMessage, { type: 'debrief.ready' }>;

/**
 * Turns a replayed event (from a fresh connect or a reconnect resume, PRD FR12) into the
 * same UI state a live push would produce. Chiefly: the session-opening AI turn is
 * generated synchronously during session creation, before any socket exists, so it can
 * *only* ever reach the client this way, never as a live `chat.message` push.
 */
function applyReplayedEvent(
  msg: ReplayMessage,
  setChat: (fn: (c: ChatEntry[]) => ChatEntry[]) => void,
  setDebrief: (d: DebriefMsg) => void,
): void {
  if (msg.type === 'chat.message') {
    const p = msg.payload as { sender?: 'candidate' | 'ai'; text?: string; kind?: AiTurnKind };
    if (p.text) {
      setChat((c) => [...c, { sender: p.sender ?? 'ai', text: p.text!, kind: p.kind, ts: msg.ts }]);
    }
    return;
  }
  if (msg.type === 'debrief.ready') {
    const p = msg.payload as Omit<DebriefMsg, 'type' | 'sessionId' | 'ts'>;
    setDebrief({ ...p, type: 'debrief.ready', sessionId: msg.sessionId, ts: msg.ts });
  }
}

export function useInterviewSocket(sessionId: string): UseInterviewSocket {
  const socketRef = useRef<InterviewSocket | null>(null);
  const [status, setStatus] = useState<'connecting' | 'open' | 'closed'>('connecting');
  const [chat, setChat] = useState<ChatEntry[]>([]);
  const [thinkingNode, setThinkingNode] = useState<'council' | 'review' | null>(null);
  const [debrief, setDebrief] =
    useState<Extract<ServerMessage, { type: 'debrief.ready' }> | null>(null);

  useEffect(() => {
    const sock = new InterviewSocket(sessionId, {
      onStatusChange: setStatus,
      onMessage: (msg) => {
        if ('seq' in msg) {
          applyReplayedEvent(msg, setChat, setDebrief);
          return;
        }
        switch (msg.type) {
          case 'chat.message':
            setChat((c) => [
              ...c,
              { sender: 'ai', text: msg.text, kind: msg.kind, ts: msg.ts },
            ]);
            setThinkingNode(null);
            break;
          case 'ai.thinking':
            setThinkingNode(msg.node);
            break;
          case 'debrief.ready':
            setDebrief(msg);
            break;
          default:
            break;
        }
      },
    });
    socketRef.current = sock;
    sock.connect();
    return () => sock.close();
  }, [sessionId]);

  const now = () => Date.now();
  const send = (msg: ClientMessage) => socketRef.current?.send(msg);

  const sendPartial = useCallback(
    (text: string) => send({ type: 'transcript.partial', sessionId, text, ts: now() }),
    [sessionId],
  );

  const sendFinal = useCallback(
    (text: string) => {
      send({ type: 'transcript.final', sessionId, text, ts: now() });
      setChat((c) => [...c, { sender: 'candidate', text, ts: now() }]);
    },
    [sessionId],
  );

  const sendSnapshot = useCallback(
    (d: Diagram) =>
      send({ type: 'diagram.snapshot', sessionId, nodes: d.nodes, edges: d.edges, ts: now() }),
    [sessionId],
  );

  const submitDiagram = useCallback(
    (d: Diagram) => send({ type: 'diagram.locked', sessionId, finalDiagram: d, ts: now() }),
    [sessionId],
  );

  const endSession = useCallback(
    () => send({ type: 'session.end', sessionId, ts: now() }),
    [sessionId],
  );

  return {
    status,
    chat,
    thinkingNode,
    debrief,
    sendPartial,
    sendFinal,
    sendSnapshot,
    submitDiagram,
    endSession,
  };
}

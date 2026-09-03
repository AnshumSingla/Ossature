// Mirror of the backend WebSocket protocol (PRD 6.4). One object per frame; `type` routes it.

export interface DiagramNode {
  id: string;
  label: string;
  x: number;
  y: number;
}

export interface DiagramEdge {
  id: string;
  source: string;
  target: string;
  label?: string;
}

export interface Diagram {
  nodes: DiagramNode[];
  edges: DiagramEdge[];
}

export type AiTurnKind = 'clarifying' | 'probing' | 'filler' | 'flaw';

interface Base {
  sessionId: string;
  ts: number;
}

export type ClientMessage =
  | (Base & { type: 'transcript.partial'; text: string })
  | (Base & { type: 'transcript.final'; text: string })
  | (Base & { type: 'diagram.snapshot'; nodes: DiagramNode[]; edges: DiagramEdge[] })
  | (Base & { type: 'diagram.locked'; finalDiagram: Diagram })
  | (Base & { type: 'chat.message'; sender: 'candidate'; text: string })
  | (Base & { type: 'session.end' });

export type ServerMessage =
  | (Base & { type: 'chat.message'; sender: 'ai'; text: string; kind: AiTurnKind })
  | (Base & { type: 'ai.thinking'; node: 'council' | 'review' })
  | (Base & {
      type: 'debrief.ready';
      strengths: string[];
      gaps: string[];
      unresolved: string[];
      referenceComparison: string;
    });

// Events replayed on reconnect are forwarded with a `seq` and a raw `payload`.
export type ReplayMessage = Base & { type: string; seq: number; payload: unknown };

export type InterviewMessage = ClientMessage | ServerMessage;

// Reconnecting WebSocket wrapper for the interview channel (PRD 6.4).
// Tracks the last replayed `seq` so a reconnect resumes without data loss (NFR reliability).

import type { ClientMessage, ReplayMessage, ServerMessage } from '../types/protocol';
import { getToken } from './auth';

const WS_BASE = import.meta.env.VITE_WS_URL ?? 'ws://localhost:8080/ws/interview';

export interface InterviewSocketHandlers {
  onMessage: (msg: ServerMessage | ReplayMessage) => void;
  onStatusChange?: (status: 'connecting' | 'open' | 'closed') => void;
}

export class InterviewSocket {
  private readonly sessionId: string;
  private readonly handlers: InterviewSocketHandlers;
  private ws: WebSocket | null = null;
  private lastSeq = 0;
  private closedByCaller = false;
  private retryMs = 1000;

  constructor(sessionId: string, handlers: InterviewSocketHandlers) {
    this.sessionId = sessionId;
    this.handlers = handlers;
  }

  connect(): void {
    this.closedByCaller = false;
    this.handlers.onStatusChange?.('connecting');
    const token = getToken() ?? '';
    const url = `${WS_BASE}?sessionId=${encodeURIComponent(this.sessionId)}&lastSeq=${this.lastSeq}&token=${encodeURIComponent(token)}`;
    const ws = new WebSocket(url);
    this.ws = ws;

    ws.onopen = () => {
      this.retryMs = 1000;
      this.handlers.onStatusChange?.('open');
    };

    ws.onmessage = (ev) => {
      const msg = JSON.parse(ev.data as string) as (ServerMessage | ReplayMessage) & {
        seq?: number;
      };
      if (typeof msg.seq === 'number') this.lastSeq = Math.max(this.lastSeq, msg.seq);
      this.handlers.onMessage(msg);
    };

    ws.onclose = () => {
      this.handlers.onStatusChange?.('closed');
      if (!this.closedByCaller) {
        setTimeout(() => this.connect(), this.retryMs);
        this.retryMs = Math.min(this.retryMs * 2, 15000);
      }
    };
  }

  send(msg: ClientMessage): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(msg));
    }
  }

  close(): void {
    this.closedByCaller = true;
    this.ws?.close();
  }
}

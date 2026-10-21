// Thin REST client for the Spring Boot session lifecycle API (PRD 6.2).

import { session } from './session';
import type { SessionInfo } from './sessionManager';
import type { Diagram, ReplayMessage } from '../types/protocol';

// Carries the server's own explanation (Spring's ProblemDetail `detail`) so screens can show
// the real reason a request failed instead of a guess.
export class ApiError extends Error {
  status: number;
  detail?: string;

  constructor(message: string, status: number, detail?: string) {
    super(message);
    this.status = status;
    this.detail = detail;
  }
}

/** What to show the user for a failed call: the server's own explanation, never a status code. */
export function errorMessage(e: unknown): string {
  if (e instanceof ApiError) {
    return e.detail ?? 'The server couldn’t handle that request. Please try again in a moment.';
  }
  if (e instanceof TypeError) return 'Can’t reach the server. Check that the backend is running.';
  return 'Something went wrong. Please try again.';
}

async function json<T>(path: string, init?: RequestInit): Promise<T> {
  // session.authFetch adds the in-memory access token and, on a 401, refreshes it once and retries.
  const res = await session.authFetch(path, {
    ...init,
    headers: { 'Content-Type': 'application/json' },
  });
  if (!res.ok) {
    const body = await res.json().catch(() => null);
    throw new ApiError(
      `${init?.method ?? 'GET'} ${path} -> ${res.status}`,
      res.status,
      typeof body?.detail === 'string' ? body.detail : undefined,
    );
  }
  return res.status === 204 ? (undefined as T) : ((await res.json()) as T);
}

// `debrief`/`diagram` are backed by a nullable jsonb column returned as a raw string body -
// Spring writes an empty body for a null String, which `res.json()` can't parse, so these
// two need their own empty-body-means-null handling instead of the generic helper above.
async function jsonOrNull<T>(path: string): Promise<T | null> {
  const res = await session.authFetch(path);
  if (!res.ok) {
    throw new Error(`GET ${path} -> ${res.status}`);
  }
  const text = await res.text();
  return text ? (JSON.parse(text) as T) : null;
}

export interface QuestionView {
  id: string;
  slug: string;
  title: string;
  prompt: string;
  source: string;
  status: string;
  hasVettedReference: boolean;
}

export interface SessionView {
  id: string;
  questionId: string;
  state: string;
  diagramLocked: boolean;
  startedAt: string;
  submittedAt: string | null;
  endedAt: string | null;
}

export type AuthResponse = SessionInfo;

export interface DebriefPayload {
  strengths: string[];
  gaps: string[];
  unresolved: string[];
  referenceComparison: string;
}

export const api = {
  // credentials: 'include' so the browser accepts the httpOnly refresh cookie these calls set.
  signup: (email: string, password: string, displayName: string) =>
    json<AuthResponse>('/api/auth/signup', {
      method: 'POST',
      credentials: 'include',
      body: JSON.stringify({ email, password, displayName }),
    }),
  login: (email: string, password: string, rememberMe: boolean) =>
    json<AuthResponse>('/api/auth/login', {
      method: 'POST',
      credentials: 'include',
      body: JSON.stringify({ email, password, rememberMe }),
    }),
  listQuestions: () => json<QuestionView[]>('/api/questions'),
  getQuestion: (id: string) => json<QuestionView>(`/api/questions/${id}`),
  submitQuestion: (title: string, prompt: string) =>
    json<QuestionView>('/api/questions', {
      method: 'POST',
      body: JSON.stringify({ title, prompt }),
    }),
  listPendingQuestions: () => json<QuestionView[]>('/api/questions/pending'),
  approveQuestion: (id: string) => json<QuestionView>(`/api/questions/${id}/approve`, { method: 'POST' }),
  rejectQuestion: (id: string) => json<QuestionView>(`/api/questions/${id}/reject`, { method: 'POST' }),
  createSession: (questionId: string) =>
    json<SessionView>('/api/sessions', {
      method: 'POST',
      body: JSON.stringify({ questionId }),
    }),
  getSession: (id: string) => json<SessionView>(`/api/sessions/${id}`),
  /** One-time, 30-second ticket for opening this session's WebSocket (replaces the login token in the URL). */
  wsTicket: (id: string) =>
    json<{ ticket: string; expiresInSeconds: number }>(`/api/sessions/${id}/ws-ticket`, { method: 'POST' }),
  listSessions: () => json<SessionView[]>('/api/sessions'),
  getDebrief: (id: string) => jsonOrNull<DebriefPayload>(`/api/sessions/${id}/debrief`),
  getSessionDiagram: (id: string) => jsonOrNull<Diagram>(`/api/sessions/${id}/diagram`),
  getSessionEvents: (id: string) => json<ReplayMessage[]>(`/api/sessions/${id}/events`),
};

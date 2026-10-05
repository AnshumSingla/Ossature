// Thin REST client for the Spring Boot session lifecycle API (PRD 6.2).

import { getToken } from './auth';
import type { Diagram, ReplayMessage } from '../types/protocol';

const BASE = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

async function json<T>(path: string, init?: RequestInit): Promise<T> {
  const token = getToken();
  const res = await fetch(`${BASE}${path}`, {
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    ...init,
  });
  if (!res.ok) {
    throw new Error(`${init?.method ?? 'GET'} ${path} -> ${res.status}`);
  }
  return res.status === 204 ? (undefined as T) : ((await res.json()) as T);
}

// `debrief`/`diagram` are backed by a nullable jsonb column returned as a raw string body -
// Spring writes an empty body for a null String, which `res.json()` can't parse, so these
// two need their own empty-body-means-null handling instead of the generic helper above.
async function jsonOrNull<T>(path: string): Promise<T | null> {
  const token = getToken();
  const res = await fetch(`${BASE}${path}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
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

export interface AuthResponse {
  token: string;
  userId: string;
  email: string;
  displayName: string;
  isOwner: boolean;
}

export interface DebriefPayload {
  strengths: string[];
  gaps: string[];
  unresolved: string[];
  referenceComparison: string;
}

export const api = {
  signup: (email: string, password: string, displayName: string) =>
    json<AuthResponse>('/api/auth/signup', {
      method: 'POST',
      body: JSON.stringify({ email, password, displayName }),
    }),
  login: (email: string, password: string) =>
    json<AuthResponse>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
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
  listSessions: () => json<SessionView[]>('/api/sessions'),
  getDebrief: (id: string) => jsonOrNull<DebriefPayload>(`/api/sessions/${id}/debrief`),
  getSessionDiagram: (id: string) => jsonOrNull<Diagram>(`/api/sessions/${id}/diagram`),
  getSessionEvents: (id: string) => json<ReplayMessage[]>(`/api/sessions/${id}/events`),
};

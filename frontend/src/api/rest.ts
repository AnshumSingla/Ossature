// Thin REST client for the Spring Boot session lifecycle API (PRD 6.2).

import { getToken } from './auth';

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

export interface QuestionView {
  id: string;
  slug: string;
  title: string;
  prompt: string;
  source: string;
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
  createSession: (questionId: string) =>
    json<SessionView>('/api/sessions', {
      method: 'POST',
      body: JSON.stringify({ questionId }),
    }),
  getSession: (id: string) => json<SessionView>(`/api/sessions/${id}`),
  listSessions: () => json<SessionView[]>('/api/sessions'),
  getDebrief: (id: string) => json<string>(`/api/sessions/${id}/debrief`),
};

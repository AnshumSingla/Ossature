// Thin REST client for the Spring Boot session lifecycle API (PRD 6.2).

const BASE = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

async function json<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    headers: { 'Content-Type': 'application/json' },
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

export const api = {
  listQuestions: () => json<QuestionView[]>('/api/questions'),
  getQuestion: (id: string) => json<QuestionView>(`/api/questions/${id}`),
  createSession: (userId: string, questionId: string) =>
    json<SessionView>('/api/sessions', {
      method: 'POST',
      body: JSON.stringify({ userId, questionId }),
    }),
  getSession: (id: string) => json<SessionView>(`/api/sessions/${id}`),
  listSessions: (userId: string) => json<SessionView[]>(`/api/sessions?userId=${userId}`),
  getDebrief: (id: string) => json<string>(`/api/sessions/${id}/debrief`),
};

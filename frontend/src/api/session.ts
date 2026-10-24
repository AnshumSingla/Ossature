// The app's one login session, wired to the real fetch and timers (logic in sessionManager.ts).

import { createSessionManager } from './sessionManager';

export const BASE = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

export const session = createSessionManager({
  baseUrl: BASE,
  fetchFn: (input, init) => fetch(input, init),
  setTimer: (fn, ms) => setTimeout(fn, ms),
  clearTimer: (handle) => clearTimeout(handle as ReturnType<typeof setTimeout>),
});

export function getToken(): string | null {
  return session.getToken();
}

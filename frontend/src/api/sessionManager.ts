// The browser side of the login session (H10).
//
// The access token is short-lived (15 minutes) and is kept ONLY in this module's memory - never in
// localStorage/sessionStorage - so a script injected into the page cannot lift a long-lived credential
// out of storage. What keeps the user signed in is the server's httpOnly refresh cookie, which
// JavaScript cannot read at all; this module just asks the server to swap it for a fresh access token
// (silently, before the old one expires, and again on a 401), and to end the session on logout.
//
// Pure logic with injected fetch/timers so it can be unit-tested; api/session.ts wires in the real ones.

export interface SessionInfo {
  token: string;
  /** Lifetime of `token`, from the server, so a refresh can be scheduled before it runs out. */
  expiresInSeconds: number;
  userId: string;
  email: string;
  displayName: string;
  isOwner: boolean;
}

export type RefreshOutcome =
  | { kind: 'ok'; session: SessionInfo }
  | { kind: 'ended' } // the server says this login is over (expired, revoked, logged out elsewhere)
  | { kind: 'unreachable' }; // network/server trouble: the login may well still be valid

export interface SessionDeps {
  baseUrl: string;
  fetchFn: typeof fetch;
  setTimer: (fn: () => void, ms: number) => unknown;
  clearTimer: (handle: unknown) => void;
}

/** The web client's marker on cookie-authenticated calls (the server's CSRF check requires it). */
export const CLIENT_HEADERS = { 'X-Ossature-Client': 'web' } as const;

/** Refresh when this fraction of the token's life has passed. */
const REFRESH_AT = 0.8;
const MIN_DELAY_MS = 5_000;
const RETRY_MS = [5_000, 15_000, 60_000];

export function createSessionManager(deps: SessionDeps) {
  let token: string | null = null;
  let timer: unknown = null;
  let inFlight: Promise<RefreshOutcome> | null = null;
  let retries = 0;
  const listeners = new Set<(s: SessionInfo | null) => void>();

  const emit = (s: SessionInfo | null) => listeners.forEach((l) => l(s));

  function cancelTimer() {
    if (timer !== null) deps.clearTimer(timer);
    timer = null;
  }

  function schedule(ms: number) {
    cancelTimer();
    timer = deps.setTimer(() => {
      timer = null;
      void refresh();
    }, Math.max(MIN_DELAY_MS, ms));
  }

  /** A login/signup/refresh succeeded: hold the new access token and plan the next refresh. */
  function adopt(session: SessionInfo) {
    token = session.token;
    retries = 0;
    schedule(session.expiresInSeconds * 1000 * REFRESH_AT);
    emit(session);
  }

  function clear() {
    token = null;
    cancelTimer();
    emit(null);
  }

  function refresh(): Promise<RefreshOutcome> {
    // One refresh at a time: two tabs' worth of components (or React StrictMode) asking together share it.
    if (inFlight) return inFlight;
    inFlight = (async (): Promise<RefreshOutcome> => {
      try {
        const res = await deps.fetchFn(`${deps.baseUrl}/api/auth/refresh`, {
          method: 'POST',
          credentials: 'include',
          headers: { ...CLIENT_HEADERS },
        });
        if (res.ok) {
          const session = (await res.json()) as SessionInfo;
          adopt(session);
          return { kind: 'ok', session };
        }
        if (res.status === 401 || res.status === 403) {
          clear();
          return { kind: 'ended' };
        }
        throw new Error(`refresh -> ${res.status}`);
      } catch {
        // Offline, backend restarting, 5xx: keep what we have and try again shortly.
        schedule(RETRY_MS[Math.min(retries++, RETRY_MS.length - 1)]);
        return { kind: 'unreachable' };
      } finally {
        inFlight = null;
      }
    })();
    return inFlight;
  }

  /** Ends the session on the server (revokes the refresh chain, clears the cookie), then forgets it here. */
  async function logout(): Promise<void> {
    try {
      await deps.fetchFn(`${deps.baseUrl}/api/auth/logout`, {
        method: 'POST',
        credentials: 'include',
        headers: { ...CLIENT_HEADERS },
      });
    } catch {
      // Offline: still sign out locally. The server-side session then lapses on its own expiry.
    }
    clear();
  }

  /**
   * fetch() for the API: adds the bearer token, and if the server answers 401 (the access token ran
   * out, or the page was just reloaded and holds none yet) refreshes once and retries.
   */
  async function authFetch(path: string, init: RequestInit = {}): Promise<Response> {
    const send = () =>
      deps.fetchFn(`${deps.baseUrl}${path}`, {
        ...init,
        headers: { ...(init.headers as Record<string, string> | undefined), ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      });
    let res = await send();
    // (Not for /api/auth/*: a 401 from login means wrong credentials, not an expired token.)
    if (res.status === 401 && !path.startsWith('/api/auth/')) {
      const outcome = await refresh();
      if (outcome.kind === 'ok') res = await send();
    }
    return res;
  }

  return {
    getToken: () => token,
    adopt,
    clear,
    refresh,
    logout,
    authFetch,
    /** Called with the session on every sign-in/refresh and with null when the session ends. */
    onChange(listener: (s: SessionInfo | null) => void): () => void {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
}

export type SessionManager = ReturnType<typeof createSessionManager>;

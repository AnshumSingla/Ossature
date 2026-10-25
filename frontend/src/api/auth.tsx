// The signed-in-user context (PRD 6.2 auth follow-up, H10).
//
// The access token is NOT stored anywhere here: it lives in memory inside api/session.ts and is renewed
// from the server's httpOnly refresh cookie. All that is remembered across a reload is the user's own
// profile (name, email, owner flag) - display data, not a credential - in localStorage ("Remember me") or
// sessionStorage (cleared when the tab closes). After a reload the profile shows immediately while the
// session is quietly restored from the cookie; if the server says the login is over, the user is signed out.

import { createContext, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { session } from './session';
import type { SessionInfo } from './sessionManager';

const USER_KEY = 'ossature.user';

export interface AuthedUser {
  userId: string;
  email: string;
  displayName: string;
  isOwner: boolean;
}

interface AuthState {
  user: AuthedUser | null;
  /** True until we know whether a remembered user's login is still valid (right after a page load). */
  restoring: boolean;
  /** Called with the response of a successful login/signup. */
  login: (data: SessionInfo, remember?: boolean) => void;
  logout: () => void;
}

const AuthContext = createContext<AuthState | null>(null);

function loadUser(): AuthedUser | null {
  const raw = localStorage.getItem(USER_KEY) ?? sessionStorage.getItem(USER_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as AuthedUser;
  } catch {
    return null;
  }
}

function toUser(s: SessionInfo): AuthedUser {
  return { userId: s.userId, email: s.email, displayName: s.displayName, isOwner: s.isOwner };
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthedUser | null>(loadUser);
  const [restoring, setRestoring] = useState(() => loadUser() !== null);
  const [remember, setRemember] = useState(() => localStorage.getItem(USER_KEY) !== null);
  const started = useRef(false);

  // Keep the profile in step with the session: sign-in/refresh updates it, the session ending clears it.
  useEffect(
    () =>
      session.onChange((s) => {
        setUser(s ? toUser(s) : null);
      }),
    [],
  );

  // Page load with a remembered user: get a fresh access token from the refresh cookie.
  useEffect(() => {
    if (started.current) return;
    started.current = true;
    if (loadUser() === null) return;
    void session.refresh().finally(() => setRestoring(false));
  }, []);

  useEffect(() => {
    const store = remember ? localStorage : sessionStorage;
    const other = remember ? sessionStorage : localStorage;
    if (user) {
      store.setItem(USER_KEY, JSON.stringify(user));
      other.removeItem(USER_KEY);
    } else {
      localStorage.removeItem(USER_KEY);
      sessionStorage.removeItem(USER_KEY);
    }
  }, [user, remember]);

  const value = useMemo<AuthState>(
    () => ({
      user,
      restoring,
      login: (data, rememberMe = true) => {
        setRemember(rememberMe);
        session.adopt(data); // holds the access token in memory and (via onChange) sets the user
      },
      logout: () => {
        void session.logout(); // ends the login on the server too; onChange clears the user
      },
    }),
    [user, restoring],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}

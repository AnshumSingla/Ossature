// Token storage + the signed-in-user context (PRD 6.2 auth follow-up).
// The JWT lives in localStorage (persists past closing the browser) or sessionStorage
// (cleared when the tab closes) depending on "Remember me" at login.

import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';

const USER_KEY = 'ossature.user';
const JWT_KEY = 'ossature.jwt';

export interface AuthedUser {
  userId: string;
  email: string;
  displayName: string;
  isOwner: boolean;
}

interface AuthState {
  user: AuthedUser | null;
  token: string | null;
  login: (data: AuthedUser & { token: string }, remember?: boolean) => void;
  logout: () => void;
}

const AuthContext = createContext<AuthState | null>(null);

function readStore(key: string): string | null {
  return localStorage.getItem(key) ?? sessionStorage.getItem(key);
}

function loadUser(): AuthedUser | null {
  const raw = readStore(USER_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as AuthedUser;
  } catch {
    return null;
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthedUser | null>(loadUser);
  const [token, setToken] = useState<string | null>(() => readStore(JWT_KEY));
  const [remember, setRemember] = useState(true);

  useEffect(() => {
    const store = remember ? localStorage : sessionStorage;
    const other = remember ? sessionStorage : localStorage;
    if (user && token) {
      store.setItem(USER_KEY, JSON.stringify(user));
      store.setItem(JWT_KEY, token);
      other.removeItem(USER_KEY);
      other.removeItem(JWT_KEY);
    } else {
      localStorage.removeItem(USER_KEY);
      localStorage.removeItem(JWT_KEY);
      sessionStorage.removeItem(USER_KEY);
      sessionStorage.removeItem(JWT_KEY);
    }
  }, [user, token, remember]);

  const value = useMemo<AuthState>(
    () => ({
      user,
      token,
      login: (data, rememberMe = true) => {
        setRemember(rememberMe);
        setUser({
          userId: data.userId,
          email: data.email,
          displayName: data.displayName,
          isOwner: data.isOwner,
        });
        setToken(data.token);
      },
      logout: () => {
        setUser(null);
        setToken(null);
      },
    }),
    [user, token],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}

export function getToken(): string | null {
  return readStore(JWT_KEY);
}

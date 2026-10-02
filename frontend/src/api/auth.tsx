// Token storage + the signed-in-user context (PRD 6.2 auth follow-up).
// The JWT lives in localStorage; every REST call and the WS handshake attach it.

import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';

const TOKEN_KEY = 'ossature.token';

export interface AuthedUser {
  userId: string;
  email: string;
  displayName: string;
}

interface AuthState {
  user: AuthedUser | null;
  token: string | null;
  login: (data: AuthedUser & { token: string }) => void;
  logout: () => void;
}

const AuthContext = createContext<AuthState | null>(null);

function loadUser(): AuthedUser | null {
  const raw = localStorage.getItem(TOKEN_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as AuthedUser;
  } catch {
    return null;
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthedUser | null>(loadUser);
  const [token, setToken] = useState<string | null>(() => localStorage.getItem('ossature.jwt'));

  useEffect(() => {
    if (user && token) {
      localStorage.setItem(TOKEN_KEY, JSON.stringify(user));
      localStorage.setItem('ossature.jwt', token);
    } else {
      localStorage.removeItem(TOKEN_KEY);
      localStorage.removeItem('ossature.jwt');
    }
  }, [user, token]);

  const value = useMemo<AuthState>(
    () => ({
      user,
      token,
      login: (data) => {
        setUser({ userId: data.userId, email: data.email, displayName: data.displayName });
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
  return localStorage.getItem('ossature.jwt');
}

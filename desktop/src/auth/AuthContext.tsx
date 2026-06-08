import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";

import { getAuthStatus, logout as logoutClient } from "./authClient";
import type { AuthStatus } from "./types";

export interface AuthContextValue {
  status: AuthStatus | null;
  isAuthenticated: boolean;
  /** Access token kept in memory for the API client to attach as a bearer. */
  accessToken: string | null;
  /** True until the initial shell auth status has been hydrated. */
  loading: boolean;
  /** Adopt a status returned by a login call (no extra round-trip). */
  adoptStatus: (status: AuthStatus) => void;
  /** Drop the local session without a shell call (e.g. refresh failed). */
  clearSession: () => void;
  /** Revoke + clear the session via the shell. */
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus | null>(null);
  const [loading, setLoading] = useState(true);

  // Hydrate from the Rust shell (source of truth). Outside the desktop shell
  // (browser/tests) the command rejects — treat that as logged out.
  useEffect(() => {
    let active = true;
    getAuthStatus()
      .then((hydrated) => {
        if (active) setStatus(hydrated);
      })
      .catch(() => {
        if (active) setStatus(null);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, []);

  const adoptStatus = useCallback((next: AuthStatus) => {
    setStatus(next);
  }, []);

  const clearSession = useCallback(() => {
    setStatus(null);
  }, []);

  const logout = useCallback(async () => {
    try {
      const next = await logoutClient();
      setStatus(next);
    } catch {
      // Even if the revoke call fails, drop the local session.
      setStatus(null);
    }
  }, []);

  const value: AuthContextValue = {
    status,
    isAuthenticated: status?.isAuthenticated ?? false,
    accessToken: status?.session?.accessToken ?? null,
    loading,
    adoptStatus,
    clearSession,
    logout,
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (context === undefined) {
    throw new Error("useAuth must be used within an AuthProvider");
  }
  return context;
}

export { AuthContext };

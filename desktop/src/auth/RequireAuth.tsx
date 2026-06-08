import type { ReactNode } from "react";
import { Navigate } from "react-router";

import { useAuth } from "./AuthContext";

/**
 * Route guard: renders children only when authenticated, otherwise redirects to
 * the login screen. Renders nothing until the initial auth status is hydrated,
 * to avoid a flash of the login screen for an already-authenticated user.
 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { isAuthenticated, loading } = useAuth();

  if (loading) {
    return null;
  }
  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }
  return <>{children}</>;
}

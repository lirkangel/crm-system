import { Navigate, Route, Routes } from "react-router";

import { LoginRoute } from "@/auth/LoginRoute";
import { RequireAuth } from "@/auth/RequireAuth";
import { AppShell } from "@/components/layout/AppShell";
import { RouteErrorBoundary } from "@/components/RouteErrorBoundary";
import { Dashboard } from "@/pages/Dashboard";

/**
 * Application route table. Mounted inside a router + AuthProvider by App.
 * Each routed page is wrapped in its own RouteErrorBoundary, so a render
 * error is contained to that page rather than tearing down the persistent
 * shell (or the whole app, for /login).
 */
export function AppRoutes() {
  return (
    <Routes>
      <Route
        path="/login"
        element={
          <RouteErrorBoundary>
            <LoginRoute />
          </RouteErrorBoundary>
        }
      />
      <Route
        element={
          <RequireAuth>
            <AppShell />
          </RequireAuth>
        }
      >
        <Route
          path="/"
          element={
            <RouteErrorBoundary>
              <Dashboard />
            </RouteErrorBoundary>
          }
        />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

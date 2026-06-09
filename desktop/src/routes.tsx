import { Navigate, Route, Routes } from "react-router";

import { LoginRoute } from "@/auth/LoginRoute";
import { PermissionRoute } from "@/auth/PermissionRoute";
import { RequireAuth } from "@/auth/RequireAuth";
import { AppShell } from "@/components/layout/AppShell";
import { RouteErrorBoundary } from "@/components/RouteErrorBoundary";
import { Dashboard } from "@/pages/Dashboard";
import { RolesPage } from "@/pages/admin/RolesPage";
import { UsersPage } from "@/pages/admin/UsersPage";

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
        <Route
          path="/admin/users"
          element={
            <RouteErrorBoundary>
              <PermissionRoute permission="core.users.read">
                <UsersPage />
              </PermissionRoute>
            </RouteErrorBoundary>
          }
        />
        <Route
          path="/admin/roles"
          element={
            <RouteErrorBoundary>
              <PermissionRoute permission="core.roles.read">
                <RolesPage />
              </PermissionRoute>
            </RouteErrorBoundary>
          }
        />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

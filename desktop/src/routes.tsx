import { Navigate, Route, Routes } from "react-router";

import { LoginRoute } from "@/auth/LoginRoute";
import { RequireAuth } from "@/auth/RequireAuth";
import { Dashboard } from "@/pages/Dashboard";

/** Application route table. Mounted inside a router + AuthProvider by App. */
export function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<LoginRoute />} />
      <Route
        path="/"
        element={
          <RequireAuth>
            <Dashboard />
          </RequireAuth>
        }
      />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

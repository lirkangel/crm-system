import { render, screen } from "@testing-library/react";
import { I18nextProvider } from "react-i18next";
import { MemoryRouter, Route, Routes } from "react-router";
import { describe, expect, it, vi } from "vitest";

vi.mock("@/auth/useCurrentUser", () => ({ useCurrentUser: vi.fn() }));

import { useCurrentUser } from "@/auth/useCurrentUser";
import { AuthContext, type AuthContextValue } from "@/auth/AuthContext";
import type { CurrentUser } from "@/auth/types";
import i18n from "@/i18n";
import { PermissionRoute } from "./PermissionRoute";

const authValue: AuthContextValue = {
  status: null,
  isAuthenticated: true,
  accessToken: "tok.jwt",
  loading: false,
  adoptStatus: vi.fn(),
  clearSession: vi.fn(),
  logout: vi.fn(async () => {}),
};

const adminUser: CurrentUser = {
  id: "u1",
  username: "admin",
  roles: ["admin"],
  permissions: ["core.users.read"],
};

function renderRoute(user: CurrentUser | null, path: string) {
  vi.mocked(useCurrentUser).mockReturnValue({ currentUser: user, loading: false });
  return render(
    <I18nextProvider i18n={i18n}>
      <AuthContext.Provider value={authValue}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/" element={<div>home</div>} />
            <Route
              path="/admin/users"
              element={
                <PermissionRoute permission="core.users.read">
                  <div>users page</div>
                </PermissionRoute>
              }
            />
          </Routes>
        </MemoryRouter>
      </AuthContext.Provider>
    </I18nextProvider>,
  );
}

describe("PermissionRoute", () => {
  it("renders children when the user holds the required permission", () => {
    renderRoute(adminUser, "/admin/users");
    expect(screen.getByText("users page")).toBeInTheDocument();
  });

  it("redirects to / when the user lacks the required permission", () => {
    const viewer: CurrentUser = { id: "u2", username: "viewer", roles: ["viewer"], permissions: [] };
    renderRoute(viewer, "/admin/users");
    expect(screen.queryByText("users page")).not.toBeInTheDocument();
    expect(screen.getByText("home")).toBeInTheDocument();
  });

  it("redirects to / when the current user is null (not yet loaded)", () => {
    renderRoute(null, "/admin/users");
    expect(screen.queryByText("users page")).not.toBeInTheDocument();
    expect(screen.getByText("home")).toBeInTheDocument();
  });
});

import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router";
import { describe, expect, it } from "vitest";

import { AuthContext, type AuthContextValue } from "./AuthContext";
import { RequireAuth } from "./RequireAuth";

const baseValue: AuthContextValue = {
  status: null,
  isAuthenticated: false,
  accessToken: null,
  loading: false,
  adoptStatus: () => {},
  clearSession: () => {},
  logout: async () => {},
};

function renderGuarded(value: AuthContextValue) {
  return render(
    <AuthContext.Provider value={value}>
      <MemoryRouter initialEntries={["/"]}>
        <Routes>
          <Route path="/login" element={<div>login marker</div>} />
          <Route
            path="/"
            element={
              <RequireAuth>
                <div>protected marker</div>
              </RequireAuth>
            }
          />
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

describe("RequireAuth", () => {
  it("renders the protected content when authenticated", () => {
    renderGuarded({ ...baseValue, isAuthenticated: true });

    expect(screen.getByText("protected marker")).toBeInTheDocument();
    expect(screen.queryByText("login marker")).not.toBeInTheDocument();
  });

  it("redirects to /login when unauthenticated", () => {
    renderGuarded({ ...baseValue, isAuthenticated: false });

    expect(screen.getByText("login marker")).toBeInTheDocument();
    expect(screen.queryByText("protected marker")).not.toBeInTheDocument();
  });

  it("renders neither while the auth status is still loading", () => {
    renderGuarded({ ...baseValue, loading: true });

    expect(screen.queryByText("protected marker")).not.toBeInTheDocument();
    expect(screen.queryByText("login marker")).not.toBeInTheDocument();
  });
});

import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { getAuthStatus, logout as logoutClient } from "./authClient";
import { AuthProvider, useAuth } from "./AuthContext";
import type { AuthStatus } from "./types";

vi.mock("./authClient", () => ({
  getAuthStatus: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
}));

const getAuthStatusMock = vi.mocked(getAuthStatus);
const logoutMock = vi.mocked(logoutClient);

const authedStatus: AuthStatus = {
  session: {
    accessToken: "access.jwt",
    accessTokenExpiresAt: "2030-01-01T00:15:00Z",
    refreshToken: "00000000-0000-0000-0000-000000000001",
    refreshTokenExpiresAt: "2030-01-08T00:00:00Z",
    tokenType: "Bearer",
  },
  pendingLogoutRevoke: null,
  isAuthenticated: true,
};

const loggedOutStatus: AuthStatus = {
  session: null,
  pendingLogoutRevoke: null,
  isAuthenticated: false,
};

function Probe() {
  const { isAuthenticated, loading, accessToken, adoptStatus, logout, clearSession } = useAuth();
  return (
    <div>
      <span data-testid="loading">{String(loading)}</span>
      <span data-testid="authed">{String(isAuthenticated)}</span>
      <span data-testid="token">{accessToken ?? ""}</span>
      <button onClick={() => adoptStatus(authedStatus)}>adopt</button>
      <button onClick={() => void logout()}>logout</button>
      <button onClick={() => clearSession()}>clear</button>
    </div>
  );
}

function renderProbe() {
  return render(
    <AuthProvider>
      <Probe />
    </AuthProvider>,
  );
}

describe("AuthContext", () => {
  beforeEach(() => {
    getAuthStatusMock.mockReset();
    logoutMock.mockReset();
    getAuthStatusMock.mockResolvedValue(loggedOutStatus);
    logoutMock.mockResolvedValue(loggedOutStatus);
  });

  it("hydrates from the shell auth status on mount", async () => {
    getAuthStatusMock.mockResolvedValue(authedStatus);

    renderProbe();

    await waitFor(() => expect(screen.getByTestId("loading")).toHaveTextContent("false"));
    expect(screen.getByTestId("authed")).toHaveTextContent("true");
    expect(screen.getByTestId("token")).toHaveTextContent("access.jwt");
  });

  it("treats a hydration failure (no shell) as logged out", async () => {
    getAuthStatusMock.mockRejectedValue(new Error("unavailable outside the desktop shell"));

    renderProbe();

    await waitFor(() => expect(screen.getByTestId("loading")).toHaveTextContent("false"));
    expect(screen.getByTestId("authed")).toHaveTextContent("false");
  });

  it("becomes authenticated after adopting a login result", async () => {
    renderProbe();
    await waitFor(() => expect(screen.getByTestId("loading")).toHaveTextContent("false"));

    await userEvent.click(screen.getByRole("button", { name: "adopt" }));

    expect(screen.getByTestId("authed")).toHaveTextContent("true");
    expect(screen.getByTestId("token")).toHaveTextContent("access.jwt");
  });

  it("clearSession drops the session without calling the shell", async () => {
    getAuthStatusMock.mockResolvedValue(authedStatus);
    renderProbe();
    await waitFor(() => expect(screen.getByTestId("authed")).toHaveTextContent("true"));

    await userEvent.click(screen.getByRole("button", { name: "clear" }));

    expect(screen.getByTestId("authed")).toHaveTextContent("false");
    expect(logoutMock).not.toHaveBeenCalled();
  });

  it("clears the session and calls the shell logout command on logout", async () => {
    getAuthStatusMock.mockResolvedValue(authedStatus);
    renderProbe();
    await waitFor(() => expect(screen.getByTestId("authed")).toHaveTextContent("true"));

    await userEvent.click(screen.getByRole("button", { name: "logout" }));

    await waitFor(() => expect(screen.getByTestId("authed")).toHaveTextContent("false"));
    expect(logoutMock).toHaveBeenCalledTimes(1);
  });
});

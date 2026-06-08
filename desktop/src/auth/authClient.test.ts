import { beforeEach, describe, expect, it, vi } from "vitest";

import { invoke } from "@/lib/tauri";
import type { AuthStatus } from "./types";
import { getAuthStatus, login, logout, refresh } from "./authClient";

vi.mock("@/lib/tauri", () => ({
  invoke: vi.fn(),
}));

const invokeMock = vi.mocked(invoke);

const authenticatedStatus: AuthStatus = {
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

describe("authClient", () => {
  beforeEach(() => {
    invokeMock.mockReset();
  });

  it("login invokes the `login` command with username and password", async () => {
    invokeMock.mockResolvedValue(authenticatedStatus);

    const result = await login("alice", "secret");

    expect(invokeMock).toHaveBeenCalledWith("login", { username: "alice", password: "secret" });
    expect(result).toEqual(authenticatedStatus);
  });

  it("logout invokes the `logout` command", async () => {
    invokeMock.mockResolvedValue({ ...authenticatedStatus, session: null, isAuthenticated: false });

    await logout();

    expect(invokeMock).toHaveBeenCalledWith("logout");
  });

  it("refresh invokes the `refresh` command", async () => {
    invokeMock.mockResolvedValue(authenticatedStatus);

    await refresh();

    expect(invokeMock).toHaveBeenCalledWith("refresh");
  });

  it("getAuthStatus invokes the `auth_status` command", async () => {
    invokeMock.mockResolvedValue(authenticatedStatus);

    await getAuthStatus();

    expect(invokeMock).toHaveBeenCalledWith("auth_status");
  });
});

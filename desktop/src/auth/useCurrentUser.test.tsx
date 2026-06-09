import { renderHook, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

vi.mock("@/api/ApiProvider", () => ({ useApi: vi.fn() }));

import { useApi } from "@/api/ApiProvider";
import { AuthContext, type AuthContextValue } from "@/auth/AuthContext";
import type { ApiClient } from "@/api/client";
import { useCurrentUser } from "./useCurrentUser";
import type { CurrentUser } from "./types";

const mockUser: CurrentUser = {
  id: "u1",
  username: "admin",
  roles: ["admin"],
  permissions: ["core.users.read"],
};

const baseAuthValue: AuthContextValue = {
  status: null,
  isAuthenticated: false,
  accessToken: null,
  loading: false,
  adoptStatus: vi.fn(),
  clearSession: vi.fn(),
  logout: vi.fn(async () => {}),
};

function makeWrapper(isAuthenticated: boolean) {
  const authValue: AuthContextValue = {
    ...baseAuthValue,
    isAuthenticated,
    accessToken: isAuthenticated ? "tok.jwt" : null,
  };
  return function Wrapper({ children }: { children: ReactNode }) {
    return <AuthContext.Provider value={authValue}>{children}</AuthContext.Provider>;
  };
}

describe("useCurrentUser", () => {
  it("fetches /api/v1/auth/me and returns user data when authenticated", async () => {
    const mockGet = vi.fn().mockResolvedValue(mockUser);
    vi.mocked(useApi).mockReturnValue({ get: mockGet } as unknown as ApiClient);

    const { result } = renderHook(() => useCurrentUser(), {
      wrapper: makeWrapper(true),
    });

    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.currentUser).toEqual(mockUser);
    expect(mockGet).toHaveBeenCalledWith("/api/v1/auth/me");
  });

  it("skips the fetch and returns null immediately when not authenticated", () => {
    vi.mocked(useApi).mockReturnValue({ get: vi.fn() } as unknown as ApiClient);

    const { result } = renderHook(() => useCurrentUser(), {
      wrapper: makeWrapper(false),
    });

    expect(result.current.currentUser).toBeNull();
    expect(result.current.loading).toBe(false);
  });

  it("returns null gracefully when the /auth/me call rejects", async () => {
    const mockGet = vi.fn().mockRejectedValue(new Error("network error"));
    vi.mocked(useApi).mockReturnValue({ get: mockGet } as unknown as ApiClient);

    const { result } = renderHook(() => useCurrentUser(), {
      wrapper: makeWrapper(true),
    });

    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.currentUser).toBeNull();
  });
});

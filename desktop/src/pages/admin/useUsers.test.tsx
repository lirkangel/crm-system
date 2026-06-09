import { renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

vi.mock("@/api/ApiProvider", () => ({ useApi: vi.fn() }));
vi.mock("@/auth/AuthContext", () => ({ useAuth: vi.fn() }));

import { useApi } from "@/api/ApiProvider";
import { useAuth } from "@/auth/AuthContext";
import type { AdminUser } from "./adminTypes";
import { useUsers } from "./useUsers";

const mockUsers: AdminUser[] = [
  { id: "u1", username: "admin", email: "admin@test.com", enabled: true },
  { id: "u2", username: "viewer", email: "viewer@test.com", enabled: false },
];

describe("useUsers", () => {
  it("returns users when the API responds successfully", async () => {
    vi.mocked(useAuth).mockReturnValue({
      isAuthenticated: true,
    } as ReturnType<typeof useAuth>);
    vi.mocked(useApi).mockReturnValue({
      get: vi.fn().mockResolvedValue(mockUsers),
    } as unknown as ReturnType<typeof useApi>);

    const { result } = renderHook(() => useUsers());

    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.users).toEqual(mockUsers);
    expect(result.current.error).toBeNull();
  });

  it("returns empty array and error string when the API rejects", async () => {
    vi.mocked(useAuth).mockReturnValue({
      isAuthenticated: true,
    } as ReturnType<typeof useAuth>);
    vi.mocked(useApi).mockReturnValue({
      get: vi.fn().mockRejectedValue(new Error("Network error")),
    } as unknown as ReturnType<typeof useApi>);

    const { result } = renderHook(() => useUsers());

    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.users).toEqual([]);
    expect(result.current.error).toBe("load_failed");
  });

  it("returns empty users and does not fetch when not authenticated", () => {
    vi.mocked(useAuth).mockReturnValue({
      isAuthenticated: false,
    } as ReturnType<typeof useAuth>);
    const mockGet = vi.fn();
    vi.mocked(useApi).mockReturnValue({
      get: mockGet,
    } as unknown as ReturnType<typeof useApi>);

    const { result } = renderHook(() => useUsers());

    expect(result.current.users).toEqual([]);
    expect(result.current.loading).toBe(false);
    expect(result.current.error).toBeNull();
    expect(mockGet).not.toHaveBeenCalled();
  });
});

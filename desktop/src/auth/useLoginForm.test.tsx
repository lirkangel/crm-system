import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { login } from "./authClient";
import type { AuthStatus } from "./types";
import { useLoginForm } from "./useLoginForm";

vi.mock("./authClient", () => ({
  login: vi.fn(),
}));

const loginMock = vi.mocked(login);

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

describe("useLoginForm", () => {
  beforeEach(() => {
    loginMock.mockReset();
  });

  it("blocks submit and reports field errors when the form is empty", async () => {
    const onSuccess = vi.fn();
    const { result } = renderHook(() => useLoginForm({ onSuccess }));

    await act(async () => {
      await result.current.submit();
    });

    expect(result.current.errors).toEqual({
      username: "auth.errors.usernameRequired",
      password: "auth.errors.passwordRequired",
    });
    expect(loginMock).not.toHaveBeenCalled();
    expect(onSuccess).not.toHaveBeenCalled();
  });

  it("calls login and onSuccess with valid credentials", async () => {
    const onSuccess = vi.fn();
    loginMock.mockResolvedValue(authenticatedStatus);
    const { result } = renderHook(() => useLoginForm({ onSuccess }));

    act(() => {
      result.current.setField("username", "alice");
      result.current.setField("password", "secret");
    });
    await act(async () => {
      await result.current.submit();
    });

    expect(loginMock).toHaveBeenCalledWith("alice", "secret");
    expect(onSuccess).toHaveBeenCalledWith(authenticatedStatus);
    expect(result.current.errors).toEqual({});
    expect(result.current.formError).toBeNull();
  });

  it("surfaces a form-level error and skips onSuccess when login fails", async () => {
    const onSuccess = vi.fn();
    loginMock.mockRejectedValue(new Error("invalid username or password"));
    const { result } = renderHook(() => useLoginForm({ onSuccess }));

    act(() => {
      result.current.setField("username", "alice");
      result.current.setField("password", "wrong");
    });
    await act(async () => {
      await result.current.submit();
    });

    expect(result.current.formError).toBe("auth.errors.loginFailed");
    expect(onSuccess).not.toHaveBeenCalled();
  });

  it("clears submitting after a completed submit", async () => {
    loginMock.mockResolvedValue(authenticatedStatus);
    const { result } = renderHook(() => useLoginForm());

    act(() => {
      result.current.setField("username", "alice");
      result.current.setField("password", "secret");
    });
    await act(async () => {
      await result.current.submit();
    });

    await waitFor(() => expect(result.current.submitting).toBe(false));
  });
});

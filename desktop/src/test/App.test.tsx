import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { I18nextProvider } from "react-i18next";

import App from "@/App";
import i18n from "@/i18n";
import { getAuthStatus, login } from "@/auth/authClient";
import type { AuthStatus } from "@/auth/types";

vi.mock("@/auth/authClient", () => ({
  getAuthStatus: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
  refresh: vi.fn(),
}));

// ApiProvider resolves the base URL from the shell; no shell in tests.
vi.mock("@/lib/tauri", () => ({
  invoke: vi.fn().mockRejectedValue(new Error("no shell")),
}));

const getAuthStatusMock = vi.mocked(getAuthStatus);
const loginMock = vi.mocked(login);

const loggedOutStatus: AuthStatus = {
  session: null,
  pendingLogoutRevoke: null,
  isAuthenticated: false,
};

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

function renderApp() {
  return render(
    <I18nextProvider i18n={i18n}>
      <App />
    </I18nextProvider>,
  );
}

describe("App", () => {
  beforeEach(async () => {
    getAuthStatusMock.mockReset();
    loginMock.mockReset();
    getAuthStatusMock.mockResolvedValue(loggedOutStatus);
    await i18n.changeLanguage("vi");
    window.history.pushState({}, "", "/");
  });

  it("redirects an unauthenticated user to the login screen in Vietnamese", async () => {
    renderApp();

    expect(await screen.findByText("Đăng nhập vào hệ thống CRM")).toBeInTheDocument();
  });

  it("switches visible copy to English when the language is toggled", async () => {
    renderApp();
    await screen.findByText("Đăng nhập vào hệ thống CRM");

    await userEvent.click(screen.getByRole("button", { name: /english/i }));

    expect(screen.getByText("Sign in to the CRM system")).toBeInTheDocument();
  });

  it("navigates to the dashboard after a successful login", async () => {
    loginMock.mockResolvedValue(authedStatus);
    renderApp();
    await screen.findByText("Đăng nhập vào hệ thống CRM");

    await userEvent.type(screen.getByLabelText("Tên đăng nhập"), "alice");
    await userEvent.type(screen.getByLabelText("Mật khẩu"), "secret");
    await userEvent.click(screen.getByRole("button", { name: "Đăng nhập" }));

    expect(await screen.findByText("Bạn đã đăng nhập thành công")).toBeInTheDocument();
  });
});

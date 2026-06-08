import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { I18nextProvider } from "react-i18next";

import i18n from "@/i18n";
import { login } from "./authClient";
import type { AuthStatus } from "./types";
import { LoginPage } from "./LoginPage";

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

function renderPage(onLoggedIn = vi.fn()) {
  render(
    <I18nextProvider i18n={i18n}>
      <LoginPage onLoggedIn={onLoggedIn} />
    </I18nextProvider>,
  );
  return { onLoggedIn };
}

describe("LoginPage", () => {
  beforeEach(async () => {
    loginMock.mockReset();
    await i18n.changeLanguage("vi");
  });

  it("renders the Vietnamese subtitle and field labels", () => {
    renderPage();

    expect(screen.getByText("Đăng nhập vào hệ thống CRM")).toBeInTheDocument();
    expect(screen.getByLabelText("Tên đăng nhập")).toBeInTheDocument();
    expect(screen.getByLabelText("Mật khẩu")).toBeInTheDocument();
  });

  it("shows a translated field error on empty submit and does not call login", async () => {
    renderPage();

    await userEvent.click(screen.getByRole("button", { name: "Đăng nhập" }));

    expect(await screen.findByText("Vui lòng nhập tên đăng nhập")).toBeInTheDocument();
    expect(loginMock).not.toHaveBeenCalled();
  });

  it("calls login and onLoggedIn with valid credentials", async () => {
    loginMock.mockResolvedValue(authenticatedStatus);
    const { onLoggedIn } = renderPage();

    await userEvent.type(screen.getByLabelText("Tên đăng nhập"), "alice");
    await userEvent.type(screen.getByLabelText("Mật khẩu"), "secret");
    await userEvent.click(screen.getByRole("button", { name: "Đăng nhập" }));

    await waitFor(() => expect(loginMock).toHaveBeenCalledWith("alice", "secret"));
    expect(onLoggedIn).toHaveBeenCalledWith(authenticatedStatus);
  });

  it("shows a translated form error when login fails", async () => {
    loginMock.mockRejectedValue(new Error("invalid username or password"));
    renderPage();

    await userEvent.type(screen.getByLabelText("Tên đăng nhập"), "alice");
    await userEvent.type(screen.getByLabelText("Mật khẩu"), "wrong");
    await userEvent.click(screen.getByRole("button", { name: "Đăng nhập" }));

    expect(await screen.findByText("Tên đăng nhập hoặc mật khẩu không đúng")).toBeInTheDocument();
  });
});

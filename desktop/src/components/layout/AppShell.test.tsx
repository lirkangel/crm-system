import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { I18nextProvider } from "react-i18next";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { AuthContext, type AuthContextValue } from "@/auth/AuthContext";
import i18n from "@/i18n";
import { AppShell } from "./AppShell";

const baseValue: AuthContextValue = {
  status: null,
  isAuthenticated: true,
  accessToken: "access.jwt",
  loading: false,
  adoptStatus: () => {},
  clearSession: () => {},
  logout: vi.fn(async () => {}),
};

function renderShell(value: AuthContextValue = baseValue) {
  return render(
    <I18nextProvider i18n={i18n}>
      <AuthContext.Provider value={value}>
        <MemoryRouter initialEntries={["/"]}>
          <Routes>
            <Route element={<AppShell />}>
              <Route path="/" element={<div>page content marker</div>} />
            </Route>
          </Routes>
        </MemoryRouter>
      </AuthContext.Provider>
    </I18nextProvider>,
  );
}

describe("AppShell", () => {
  beforeEach(async () => {
    await i18n.changeLanguage("vi");
  });

  it("renders the brand and nav alongside the routed page content", () => {
    renderShell();

    expect(screen.getByText("Hệ thống CRM")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Bảng điều khiển" })).toBeInTheDocument();
    expect(screen.getByText("page content marker")).toBeInTheDocument();
  });

  it("renders the sync status pill and language toggle", () => {
    renderShell();

    expect(screen.getByText("Đã đồng bộ")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "English" })).toBeInTheDocument();
  });

  it("calls logout from the header when the sign-out button is clicked", async () => {
    const logout = vi.fn(async () => {});
    renderShell({ ...baseValue, logout });

    await userEvent.click(screen.getByRole("button", { name: "Đăng xuất" }));

    expect(logout).toHaveBeenCalledTimes(1);
  });
});

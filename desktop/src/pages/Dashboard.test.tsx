import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { I18nextProvider } from "react-i18next";

import i18n from "@/i18n";
import { AuthContext, type AuthContextValue } from "@/auth/AuthContext";
import { Dashboard } from "./Dashboard";

const baseValue: AuthContextValue = {
  status: null,
  isAuthenticated: true,
  accessToken: "access.jwt",
  loading: false,
  adoptStatus: () => {},
  clearSession: () => {},
  logout: vi.fn(async () => {}),
};

function renderDashboard(value: AuthContextValue = baseValue) {
  return render(
    <I18nextProvider i18n={i18n}>
      <AuthContext.Provider value={value}>
        <Dashboard />
      </AuthContext.Provider>
    </I18nextProvider>,
  );
}

describe("Dashboard", () => {
  beforeEach(async () => {
    await i18n.changeLanguage("vi");
  });

  it("renders the Vietnamese welcome copy", () => {
    renderDashboard();

    expect(screen.getByText("Bạn đã đăng nhập thành công")).toBeInTheDocument();
  });

  it("calls logout when the logout button is clicked", async () => {
    const logout = vi.fn(async () => {});
    renderDashboard({ ...baseValue, logout });

    await userEvent.click(screen.getByRole("button", { name: "Đăng xuất" }));

    await waitFor(() => expect(logout).toHaveBeenCalledTimes(1));
  });
});

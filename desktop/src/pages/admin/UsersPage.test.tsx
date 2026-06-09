import { render, screen } from "@testing-library/react";
import { I18nextProvider } from "react-i18next";
import { describe, expect, it, vi } from "vitest";

vi.mock("./useUsers", () => ({ useUsers: vi.fn() }));

import i18n from "@/i18n";
import { useUsers } from "./useUsers";
import { UsersPage } from "./UsersPage";

const mockAdminUsers = [
  { id: "u1", username: "admin", email: "admin@test.com", enabled: true },
  { id: "u2", username: "viewer", email: "viewer@test.com", enabled: false },
];

function renderPage() {
  return render(
    <I18nextProvider i18n={i18n}>
      <UsersPage />
    </I18nextProvider>,
  );
}

describe("UsersPage", () => {
  it("shows a loading indicator while data is fetching", async () => {
    vi.mocked(useUsers).mockReturnValue({ users: [], loading: true, error: null });
    renderPage();

    await i18n.changeLanguage("vi");
    expect(screen.getByRole("status")).toBeInTheDocument();
  });

  it("shows user rows in a table when users are loaded", async () => {
    await i18n.changeLanguage("vi");
    vi.mocked(useUsers).mockReturnValue({
      users: mockAdminUsers,
      loading: false,
      error: null,
    });
    renderPage();

    expect(screen.getByRole("table")).toBeInTheDocument();
    expect(screen.getByText("admin")).toBeInTheDocument();
    expect(screen.getByText("viewer")).toBeInTheDocument();
    expect(screen.getByText("admin@test.com")).toBeInTheDocument();
    expect(screen.getByText("viewer@test.com")).toBeInTheDocument();
  });

  it("shows an error message when the API call fails", async () => {
    await i18n.changeLanguage("vi");
    vi.mocked(useUsers).mockReturnValue({
      users: [],
      loading: false,
      error: "load_failed",
    });
    renderPage();

    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(screen.getByText("Không thể tải danh sách người dùng")).toBeInTheDocument();
  });

  it("shows an empty state when there are no users", async () => {
    await i18n.changeLanguage("vi");
    vi.mocked(useUsers).mockReturnValue({ users: [], loading: false, error: null });
    renderPage();

    expect(screen.getByText("Chưa có người dùng nào")).toBeInTheDocument();
  });

  it("labels enabled users as active and disabled users as inactive", async () => {
    await i18n.changeLanguage("vi");
    vi.mocked(useUsers).mockReturnValue({
      users: mockAdminUsers,
      loading: false,
      error: null,
    });
    renderPage();

    expect(screen.getByText("Hoạt động")).toBeInTheDocument();
    expect(screen.getByText("Vô hiệu")).toBeInTheDocument();
  });
});

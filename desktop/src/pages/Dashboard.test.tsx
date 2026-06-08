import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it } from "vitest";
import { I18nextProvider } from "react-i18next";

import i18n from "@/i18n";
import { Dashboard } from "./Dashboard";

function renderDashboard() {
  return render(
    <I18nextProvider i18n={i18n}>
      <Dashboard />
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
});

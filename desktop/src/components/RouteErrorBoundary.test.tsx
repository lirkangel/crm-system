import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { I18nextProvider } from "react-i18next";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import i18n from "@/i18n";
import { RouteErrorBoundary } from "./RouteErrorBoundary";

let shouldThrow = true;

function Flaky() {
  if (shouldThrow) {
    throw new Error("boom");
  }
  return <div>recovered content</div>;
}

function renderBoundary() {
  return render(
    <I18nextProvider i18n={i18n}>
      <RouteErrorBoundary>
        <Flaky />
      </RouteErrorBoundary>
    </I18nextProvider>,
  );
}

describe("RouteErrorBoundary", () => {
  beforeEach(async () => {
    shouldThrow = true;
    await i18n.changeLanguage("vi");
    // React logs caught render errors to the console — silence the expected noise.
    vi.spyOn(console, "error").mockImplementation(() => {});
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("renders children when there is no error", () => {
    shouldThrow = false;

    render(
      <I18nextProvider i18n={i18n}>
        <RouteErrorBoundary>
          <div>safe content</div>
        </RouteErrorBoundary>
      </I18nextProvider>,
    );

    expect(screen.getByText("safe content")).toBeInTheDocument();
  });

  it("contains a thrown render error and shows a localized fallback", () => {
    renderBoundary();

    expect(screen.getByText("Đã xảy ra lỗi")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Thử lại" })).toBeInTheDocument();
    expect(screen.queryByText("recovered content")).not.toBeInTheDocument();
  });

  it("re-renders children when retried after the underlying error clears", async () => {
    renderBoundary();
    shouldThrow = false;

    await userEvent.click(screen.getByRole("button", { name: "Thử lại" }));

    expect(screen.getByText("recovered content")).toBeInTheDocument();
    expect(screen.queryByText("Đã xảy ra lỗi")).not.toBeInTheDocument();
  });
});

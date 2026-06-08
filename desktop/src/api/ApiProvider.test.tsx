import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { AuthProvider } from "@/auth/AuthContext";
import { ApiProvider, useApi } from "./ApiProvider";

vi.mock("@/auth/authClient", () => ({
  getAuthStatus: vi.fn().mockResolvedValue({
    session: null,
    pendingLogoutRevoke: null,
    isAuthenticated: false,
  }),
  refresh: vi.fn(),
  logout: vi.fn(),
}));

vi.mock("@/lib/tauri", () => ({
  invoke: vi.fn().mockRejectedValue(new Error("no shell")),
}));

function Probe() {
  const api = useApi();
  return (
    <span data-testid="api">
      {String(typeof api.get === "function" && typeof api.post === "function")}
    </span>
  );
}

describe("ApiProvider", () => {
  it("provides an api client to consumers", () => {
    render(
      <AuthProvider>
        <ApiProvider>
          <Probe />
        </ApiProvider>
      </AuthProvider>,
    );

    expect(screen.getByTestId("api")).toHaveTextContent("true");
  });

  it("throws when useApi is used outside an ApiProvider", () => {
    function Bare() {
      useApi();
      return null;
    }
    const spy = vi.spyOn(console, "error").mockImplementation(() => {});
    expect(() => render(<Bare />)).toThrow(/ApiProvider/);
    spy.mockRestore();
  });
});

import { beforeEach, describe, expect, it, vi } from "vitest";

import { invoke } from "@/lib/tauri";
import { DEFAULT_BACKEND_BASE_URL, resolveBackendBaseUrl } from "./backendConfig";

vi.mock("@/lib/tauri", () => ({
  invoke: vi.fn(),
}));

const invokeMock = vi.mocked(invoke);

describe("resolveBackendBaseUrl", () => {
  beforeEach(() => {
    invokeMock.mockReset();
  });

  it("returns the base URL reported by the shell", async () => {
    invokeMock.mockResolvedValue({
      baseUrl: "http://10.0.0.5:8082",
      wsPath: "/ws",
      healthPath: "/actuator/health",
    });

    await expect(resolveBackendBaseUrl()).resolves.toBe("http://10.0.0.5:8082");
    expect(invokeMock).toHaveBeenCalledWith("backend_config");
  });

  it("falls back to the default outside the desktop shell", async () => {
    invokeMock.mockRejectedValue(new Error("unavailable outside the desktop shell"));

    await expect(resolveBackendBaseUrl()).resolves.toBe(DEFAULT_BACKEND_BASE_URL);
  });
});

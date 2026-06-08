import { beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError, createApiClient, type ApiClientDeps } from "./client";

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function ok<T>(data: T): Response {
  return jsonResponse({ success: true, code: "OK", message: "", data });
}

function makeDeps(overrides: Partial<ApiClientDeps> = {}): {
  deps: ApiClientDeps;
  fetchFn: ReturnType<typeof vi.fn>;
  refreshAccessToken: ReturnType<typeof vi.fn>;
  onAuthFailure: ReturnType<typeof vi.fn>;
} {
  const fetchFn = vi.fn();
  const refreshAccessToken = vi.fn();
  const onAuthFailure = vi.fn();
  const deps: ApiClientDeps = {
    baseUrl: "http://api.test",
    getAccessToken: () => "old-token",
    refreshAccessToken,
    onAuthFailure,
    fetchFn: fetchFn as unknown as typeof fetch,
    ...overrides,
  };
  return { deps, fetchFn, refreshAccessToken, onAuthFailure };
}

describe("createApiClient", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("attaches the bearer token and unwraps CommonResponse data", async () => {
    const { deps, fetchFn } = makeDeps();
    fetchFn.mockResolvedValue(ok({ id: 7, name: "room" }));
    const client = createApiClient(deps);

    const result = await client.get<{ id: number; name: string }>("/api/v1/rooms/7");

    expect(result).toEqual({ id: 7, name: "room" });
    const [url, init] = fetchFn.mock.calls[0];
    expect(url).toBe("http://api.test/api/v1/rooms/7");
    expect((init.headers as Record<string, string>).Authorization).toBe("Bearer old-token");
  });

  it("throws ApiError on a non-2xx response without refreshing", async () => {
    const { deps, fetchFn, refreshAccessToken } = makeDeps();
    fetchFn.mockResolvedValue(
      jsonResponse({ success: false, code: "ROOM_NOT_FOUND", message: "No room" }, 404),
    );
    const client = createApiClient(deps);

    await expect(client.get("/api/v1/rooms/9")).rejects.toMatchObject({
      name: "ApiError",
      status: 404,
      code: "ROOM_NOT_FOUND",
    });
    expect(refreshAccessToken).not.toHaveBeenCalled();
  });

  it("throws ApiError when a 2xx body reports success:false", async () => {
    const { deps, fetchFn } = makeDeps();
    fetchFn.mockResolvedValue(jsonResponse({ success: false, code: "BIZ", message: "nope" }, 200));
    const client = createApiClient(deps);

    await expect(client.get("/x")).rejects.toBeInstanceOf(ApiError);
  });

  it("on 401 refreshes once, retries with the new token, and returns data", async () => {
    const { deps, fetchFn, refreshAccessToken } = makeDeps();
    refreshAccessToken.mockResolvedValue("new-token");
    fetchFn
      .mockResolvedValueOnce(jsonResponse({ success: false, code: "AUTH", message: "expired" }, 401))
      .mockResolvedValueOnce(ok({ ok: true }));
    const client = createApiClient(deps);

    const result = await client.get<{ ok: boolean }>("/api/v1/me");

    expect(result).toEqual({ ok: true });
    expect(refreshAccessToken).toHaveBeenCalledTimes(1);
    expect(fetchFn).toHaveBeenCalledTimes(2);
    expect((fetchFn.mock.calls[1][1].headers as Record<string, string>).Authorization).toBe(
      "Bearer new-token",
    );
  });

  it("logs out and throws when refresh fails after a 401", async () => {
    const { deps, fetchFn, refreshAccessToken, onAuthFailure } = makeDeps();
    refreshAccessToken.mockRejectedValue(new Error("refresh rejected"));
    fetchFn.mockResolvedValue(jsonResponse({ success: false, code: "AUTH", message: "expired" }, 401));
    const client = createApiClient(deps);

    await expect(client.get("/api/v1/me")).rejects.toBeInstanceOf(ApiError);
    expect(onAuthFailure).toHaveBeenCalledTimes(1);
  });

  it("logs out when the retry after refresh is still 401", async () => {
    const { deps, fetchFn, refreshAccessToken, onAuthFailure } = makeDeps();
    refreshAccessToken.mockResolvedValue("new-token");
    fetchFn.mockResolvedValue(jsonResponse({ success: false, code: "AUTH", message: "expired" }, 401));
    const client = createApiClient(deps);

    await expect(client.get("/api/v1/me")).rejects.toBeInstanceOf(ApiError);
    expect(onAuthFailure).toHaveBeenCalledTimes(1);
  });

  it("shares a single refresh across concurrent 401s", async () => {
    const { deps, fetchFn, refreshAccessToken } = makeDeps();
    refreshAccessToken.mockResolvedValue("new-token");
    fetchFn.mockImplementation((_url: string, init: RequestInit) => {
      const auth = (init.headers as Record<string, string>).Authorization;
      return Promise.resolve(auth === "Bearer new-token" ? ok({ ok: true }) : jsonResponse({ success: false, code: "AUTH", message: "expired" }, 401));
    });
    const client = createApiClient(deps);

    await Promise.all([client.get("/a"), client.get("/b"), client.get("/c")]);

    expect(refreshAccessToken).toHaveBeenCalledTimes(1);
  });

  it("sends a JSON body and Content-Type on post", async () => {
    const { deps, fetchFn } = makeDeps();
    fetchFn.mockResolvedValue(ok({ created: true }));
    const client = createApiClient(deps);

    await client.post("/api/v1/rooms", { name: "201" });

    const [, init] = fetchFn.mock.calls[0];
    expect(init.method).toBe("POST");
    expect(init.body).toBe(JSON.stringify({ name: "201" }));
    expect((init.headers as Record<string, string>)["Content-Type"]).toBe("application/json");
  });
});

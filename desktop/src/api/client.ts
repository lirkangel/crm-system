/** Backend success/failure envelope (mirrors Spring `CommonResponse<T>`). */
interface CommonResponse<T> {
  success: boolean;
  code: string;
  message: string;
  data: T | null;
}

/** Error thrown for any non-success API outcome. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string | null;
  readonly body: unknown;

  constructor(status: number, code: string | null, message: string, body?: unknown) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.body = body;
  }
}

export interface ApiClientDeps {
  baseUrl: string;
  /** Current access token, or null when unauthenticated. */
  getAccessToken: () => string | null;
  /** Refreshes the session and resolves the new access token, or rejects. */
  refreshAccessToken: () => Promise<string>;
  /** Called when auth is unrecoverable (refresh failed / still 401). */
  onAuthFailure: () => void;
  /** Injectable for tests; defaults to global fetch. */
  fetchFn?: typeof fetch;
}

export interface ApiClient {
  request<T>(path: string, init?: RequestInit): Promise<T>;
  get<T>(path: string, init?: RequestInit): Promise<T>;
  post<T>(path: string, body?: unknown, init?: RequestInit): Promise<T>;
  put<T>(path: string, body?: unknown, init?: RequestInit): Promise<T>;
  del<T>(path: string, init?: RequestInit): Promise<T>;
}

export function createApiClient(deps: ApiClientDeps): ApiClient {
  const { baseUrl, getAccessToken, refreshAccessToken, onAuthFailure } = deps;
  const fetchFn = deps.fetchFn ?? fetch;

  // Single-flight refresh: concurrent 401s share one refresh call.
  let refreshing: Promise<string> | null = null;
  const refreshOnce = (): Promise<string> => {
    if (!refreshing) {
      refreshing = refreshAccessToken().finally(() => {
        refreshing = null;
      });
    }
    return refreshing;
  };

  const doFetch = (path: string, init: RequestInit | undefined, token: string | null) => {
    const headers: Record<string, string> = { ...(init?.headers as Record<string, string>) };
    if (token) {
      headers.Authorization = `Bearer ${token}`;
    }
    if (init?.body !== undefined && !headers["Content-Type"]) {
      headers["Content-Type"] = "application/json";
    }
    return fetchFn(`${baseUrl}${path}`, { ...init, headers });
  };

  async function handle<T>(res: Response): Promise<T> {
    if (!res.ok) {
      const body = await safeJson(res);
      throw new ApiError(res.status, codeOf(body), messageOf(body) ?? res.statusText, body);
    }
    const body = (await res.json()) as CommonResponse<T>;
    if (body && typeof body === "object" && "success" in body && !body.success) {
      throw new ApiError(res.status, body.code, body.message, body);
    }
    return (body?.data ?? null) as T;
  }

  async function request<T>(path: string, init?: RequestInit): Promise<T> {
    let res = await doFetch(path, init, getAccessToken());

    if (res.status === 401) {
      let newToken: string;
      try {
        newToken = await refreshOnce();
      } catch {
        onAuthFailure();
        throw new ApiError(401, "AUTH_REFRESH_FAILED", "Session expired");
      }
      res = await doFetch(path, init, newToken);
      if (res.status === 401) {
        onAuthFailure();
        throw new ApiError(401, "AUTH_REFRESH_FAILED", "Session expired");
      }
    }

    return handle<T>(res);
  }

  return {
    request,
    get: (path, init) => request(path, { ...init, method: "GET" }),
    post: (path, body, init) =>
      request(path, { ...init, method: "POST", body: serialize(body) }),
    put: (path, body, init) => request(path, { ...init, method: "PUT", body: serialize(body) }),
    del: (path, init) => request(path, { ...init, method: "DELETE" }),
  };
}

function serialize(body: unknown): BodyInit | undefined {
  return body === undefined ? undefined : JSON.stringify(body);
}

async function safeJson(res: Response): Promise<unknown> {
  try {
    return await res.json();
  } catch {
    return undefined;
  }
}

function codeOf(body: unknown): string | null {
  return isRecord(body) && typeof body.code === "string" ? body.code : null;
}

function messageOf(body: unknown): string | null {
  return isRecord(body) && typeof body.message === "string" ? body.message : null;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

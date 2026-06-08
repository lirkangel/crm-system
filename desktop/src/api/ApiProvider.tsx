import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";

import { useAuth } from "@/auth/AuthContext";
import { refresh as refreshClient } from "@/auth/authClient";
import { createApiClient, type ApiClient } from "./client";
import { DEFAULT_BACKEND_BASE_URL, resolveBackendBaseUrl } from "./backendConfig";

const ApiContext = createContext<ApiClient | undefined>(undefined);

/**
 * Builds the API client from the auth context: attaches the current access
 * token, refreshes via the shell on 401, and clears the session on failure.
 *
 * The client is rebuilt when the access token changes; its single-flight
 * refresh only needs to hold within one token epoch, which this preserves
 * (the token only changes once a refresh has already completed).
 */
export function ApiProvider({ children }: { children: ReactNode }) {
  const { accessToken, adoptStatus, clearSession } = useAuth();

  const refreshAccessToken = useCallback(async () => {
    const status = await refreshClient();
    adoptStatus(status);
    const token = status.session?.accessToken;
    if (!token) {
      throw new Error("No access token after refresh");
    }
    return token;
  }, [adoptStatus]);

  const onAuthFailure = useCallback(() => clearSession(), [clearSession]);

  const [baseUrl, setBaseUrl] = useState(DEFAULT_BACKEND_BASE_URL);
  useEffect(() => {
    let active = true;
    void resolveBackendBaseUrl().then((url) => {
      if (active) setBaseUrl(url);
    });
    return () => {
      active = false;
    };
  }, []);

  const client = useMemo(
    () =>
      createApiClient({
        baseUrl,
        getAccessToken: () => accessToken,
        refreshAccessToken,
        onAuthFailure,
      }),
    [baseUrl, accessToken, refreshAccessToken, onAuthFailure],
  );

  return <ApiContext.Provider value={client}>{children}</ApiContext.Provider>;
}

export function useApi(): ApiClient {
  const context = useContext(ApiContext);
  if (context === undefined) {
    throw new Error("useApi must be used within an ApiProvider");
  }
  return context;
}

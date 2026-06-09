import { useEffect, useState } from "react";

import { useApi } from "@/api/ApiProvider";
import { useAuth } from "./AuthContext";
import type { CurrentUser } from "./types";

export interface UseCurrentUserResult {
  currentUser: CurrentUser | null;
  loading: boolean;
}

/**
 * Fetches the current user from /api/v1/auth/me. Returns null while
 * unauthenticated or when the endpoint is unavailable (fail-closed).
 * While loading, currentUser is null so stale data from a previous
 * session is never exposed.
 */
export function useCurrentUser(): UseCurrentUserResult {
  const { isAuthenticated } = useAuth();
  const api = useApi();
  const [currentUser, setCurrentUser] = useState<CurrentUser | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!isAuthenticated) return;

    let active = true;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- intentional loading-flag pattern for manual fetch; rule is overly strict for this use case
    setLoading(true);

    api
      .get<CurrentUser>("/api/v1/auth/me")
      .then((user) => {
        if (active) {
          setCurrentUser(user);
          setLoading(false);
        }
      })
      .catch(() => {
        if (active) {
          setCurrentUser(null);
          setLoading(false);
        }
      });

    return () => {
      active = false;
    };
  }, [isAuthenticated, api]);

  if (!isAuthenticated) {
    return { currentUser: null, loading: false };
  }
  // While loading, expose null rather than stale data from a prior session.
  return { currentUser: loading ? null : currentUser, loading };
}

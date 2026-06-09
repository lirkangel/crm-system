import { useEffect, useState } from "react";

import { useApi } from "@/api/ApiProvider";
import { useAuth } from "./AuthContext";
import type { CurrentUser } from "./types";

export interface UseCurrentUserResult {
  currentUser: CurrentUser | null;
  loading: boolean;
}

export function useCurrentUser(): UseCurrentUserResult {
  const { isAuthenticated } = useAuth();
  const api = useApi();
  const [currentUser, setCurrentUser] = useState<CurrentUser | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!isAuthenticated) {
      setCurrentUser(null);
      return;
    }

    let active = true;
    setLoading(true);

    api
      .get<CurrentUser>("/api/v1/auth/me")
      .then((user) => {
        if (active) setCurrentUser(user);
      })
      .catch(() => {
        if (active) setCurrentUser(null);
      })
      .finally(() => {
        if (active) setLoading(false);
      });

    return () => {
      active = false;
    };
  }, [isAuthenticated, api]);

  return { currentUser, loading };
}

import { useEffect, useState } from "react";

import { useApi } from "@/api/ApiProvider";
import { useAuth } from "@/auth/AuthContext";
import type { AdminUser } from "./adminTypes";

export interface UseUsersResult {
  users: AdminUser[];
  loading: boolean;
  error: string | null;
}

export function useUsers(): UseUsersResult {
  const { isAuthenticated } = useAuth();
  const api = useApi();
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!isAuthenticated) return;

    let active = true;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- intentional loading-flag pattern for manual fetch
    setLoading(true);
    setError(null);

    api
      .get<AdminUser[]>("/api/v1/users")
      .then((data) => {
        if (active) {
          setUsers(data);
          setLoading(false);
        }
      })
      .catch(() => {
        if (active) {
          setError("load_failed");
          setLoading(false);
        }
      });

    return () => {
      active = false;
    };
  }, [isAuthenticated, api]);

  if (!isAuthenticated) {
    return { users: [], loading: false, error: null };
  }

  return { users, loading, error };
}

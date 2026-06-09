import type { ReactNode } from "react";
import { Navigate } from "react-router";

import { hasPermission } from "./permissions";
import { useCurrentUser } from "./useCurrentUser";

interface PermissionRouteProps {
  permission: string;
  children: ReactNode;
  redirectTo?: string;
}

/**
 * Renders children only when the current user holds the required permission.
 * Redirects to `redirectTo` (default "/") otherwise — including while the
 * user profile is still loading (fail-closed).
 */
export function PermissionRoute({ permission, children, redirectTo = "/" }: PermissionRouteProps) {
  const { currentUser } = useCurrentUser();

  if (!hasPermission(currentUser, permission)) {
    return <Navigate to={redirectTo} replace />;
  }

  return <>{children}</>;
}

import type { CurrentUser } from "./types";

export function hasPermission(user: CurrentUser | null, permission: string): boolean {
  return user !== null && user.permissions.includes(permission);
}

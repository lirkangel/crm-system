/**
 * Mirrors the Tauri shell's serialized auth types (serde camelCase).
 * Source of truth: `desktop/src-tauri/src/auth.rs` (`AuthSession`, `AuthStatus`).
 */

export interface AuthSession {
  accessToken: string;
  accessTokenExpiresAt: string;
  refreshToken: string;
  refreshTokenExpiresAt: string;
  tokenType: string;
}

export interface AuthStatus {
  session: AuthSession | null;
  pendingLogoutRevoke: string | null;
  isAuthenticated: boolean;
}

import { invoke } from "@/lib/tauri";
import type { AuthStatus } from "./types";

/**
 * Typed wrappers over the Tauri auth commands. Command names and argument
 * shapes must match `desktop/src-tauri/src/commands.rs`.
 */

export function login(username: string, password: string): Promise<AuthStatus> {
  return invoke<AuthStatus>("login", { username, password });
}

export function logout(): Promise<AuthStatus> {
  return invoke<AuthStatus>("logout");
}

export function refresh(): Promise<AuthStatus> {
  return invoke<AuthStatus>("refresh");
}

export function getAuthStatus(): Promise<AuthStatus> {
  return invoke<AuthStatus>("auth_status");
}

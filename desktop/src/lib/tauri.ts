import { invoke as tauriInvoke } from "@tauri-apps/api/core";

/** True when running inside the Tauri shell (vs a plain browser or test env). */
export function isTauri(): boolean {
  return typeof window !== "undefined" && "__TAURI_INTERNALS__" in window;
}

/**
 * Invokes a Tauri command, rejecting clearly when not running in the shell.
 * Keeps the same React build usable in a browser and under jsdom tests, where
 * the Tauri runtime is absent.
 */
export function invoke<T>(command: string, args?: Record<string, unknown>): Promise<T> {
  if (!isTauri()) {
    return Promise.reject(
      new Error(`Tauri command "${command}" is unavailable outside the desktop shell`),
    );
  }
  return tauriInvoke<T>(command, args);
}

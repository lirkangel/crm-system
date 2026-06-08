import { invoke } from "@/lib/tauri";

/** Mirrors the shell's `BackendConfig` (serde camelCase). */
interface BackendConfig {
  baseUrl: string;
  wsPath: string;
  healthPath: string;
}

/** Used in a plain browser (no shell) and as the local-dev default. */
export const DEFAULT_BACKEND_BASE_URL = "http://127.0.0.1:8082";

/**
 * Resolves the backend base URL from the Tauri shell's `backend_config`
 * command, falling back to the default when running outside the shell.
 */
export async function resolveBackendBaseUrl(): Promise<string> {
  try {
    const config = await invoke<BackendConfig>("backend_config");
    return config.baseUrl;
  } catch {
    return DEFAULT_BACKEND_BASE_URL;
  }
}

import { messageKey } from "@/lib/problem";
import { claimLink, resetLink } from "@/lib/token";

const BASE = "/api/v1/plugins/hotel/public/checkin";

export interface GuestRef {
  id: string;
  maskedName: string;
  documentType: string;
}
export interface Summary {
  hotelName: string;
  roomTypeName: string;
  checkIn: string;
  checkOut: string;
  adults: number;
  maxGuests: number;
  open: boolean;
  guests: GuestRef[];
}
export interface ScanFields {
  idType?: string | null;
  idNumber?: string | null;
  fullName?: string | null;
  dateOfBirth?: string | null;
  nationality?: string | null;
  address?: string | null;
  sex?: string | null;
  issueDate?: string | null;
}
export interface ScanResult {
  scanId: string;
  fields: ScanFields;
}
export interface Identity {
  idType: "CCCD" | "PASSPORT" | "OTHER";
  idNumber: string;
  fullName: string;
  dateOfBirth: string;
  nationality: string;
  address: string | null;
  visaNumber: string | null;
  entryDate: string | null;
  idScanId: string | null;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;
  constructor(status: number, code?: string) {
    super(code ?? `HTTP ${status}`);
    this.status = status;
    this.code = code;
  }
  get messageKey(): string {
    return messageKey(this);
  }
}

// The token lives only in this module variable: never in storage or cookies.
let token: string | null = null;

/** Takes the token from the URL fragment the first time; later calls keep the one already held. */
export function bootstrapToken(location: Location): boolean {
  const link = claimLink(location);
  if (link?.kind === "hotel") token = link.token;
  return token !== null;
}
export function resetToken(): void {
  token = null;
  resetLink();
}

async function call<T>(path: string, init: RequestInit = {}): Promise<{ status: number; data: T }> {
  const headers: Record<string, string> = { "X-Checkin-Token": token ?? "" };
  if (init.body && !(init.body instanceof FormData)) headers["Content-Type"] = "application/json";
  let res: Response;
  try {
    res = await fetch(BASE + path, { ...init, headers, cache: "no-store", credentials: "omit" });
  } catch {
    throw new ApiError(0);
  }
  if (!res.ok) {
    let code: string | undefined;
    try {
      code = (await res.json()).code;
    } catch {
      /* not JSON */
    }
    throw new ApiError(res.status, code);
  }
  if (res.status === 204) return { status: 204, data: undefined as T };
  return { status: res.status, data: (await res.json()).data as T };
}

const request = async <T>(path: string, init: RequestInit = {}): Promise<T> => (await call<T>(path, init)).data;

export const getSummary = () => request<Summary>("");

export function uploadScan(image: Blob): Promise<ScanResult> {
  const form = new FormData();
  form.append("image", image, "id.jpg");
  return request<ScanResult>("/id-scans", { method: "POST", body: form });
}

/** The server answers 201 for a new submission and 200 with the stored one when the same document was sent before. */
export async function submitGuest(identity: Identity): Promise<{ guest: GuestRef; created: boolean; status: number }> {
  const { status, data } = await call<GuestRef>("/guests", {
    method: "POST",
    body: JSON.stringify({ identity, consent: true }),
  });
  return { guest: data, created: status === 201, status };
}

export const deleteGuest = (id: string) =>
  request<void>(`/guests/${encodeURIComponent(id)}`, { method: "DELETE" });

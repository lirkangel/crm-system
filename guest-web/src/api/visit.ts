import { claimLink, resetLink } from "@/lib/token";

const BASE = "/api/v1/plugins/dental/public/visit";

export type Returning = "NONE" | "AVAILABLE" | "LOCKED";
export interface VisitSummary {
  clinicName: string;
  startsAt: string;
  durationMinutes: number;
  dentist: string | null;
  maskedName: string;
  open: boolean;
  submitted: boolean;
  returning: Returning;
  child: boolean;
}
export interface ScanFields {
  idType?: string | null;
  idNumber?: string | null;
  fullName?: string | null;
  dateOfBirth?: string | null;
  nationality?: string | null;
  address?: string | null;
}
export interface ScanResult {
  scanId: string;
  fields: ScanFields;
}
export interface VisitIdentity {
  idType: "CCCD" | "PASSPORT" | "OTHER";
  idNumber: string;
  fullName: string;
  dateOfBirth: string;
  nationality: string;
  address: string | null;
}
export interface AllergyAnswer {
  none: boolean;
  items: string[];
  otherText: string | null;
}
export interface MedicineAnswer {
  none: boolean;
  text: string | null;
}
export interface ReasonAnswer {
  code: string;
  text: string | null;
}
export interface VisitForm {
  identity: VisitIdentity | null;
  parentName?: string;
  parentPhone?: string;
  phone: string;
  allergies: AllergyAnswer;
  medicines: MedicineAnswer;
  reason: ReasonAnswer;
  consent: true;
  idScanId: string | null;
}
export interface PreviousAnswers {
  allergies: AllergyAnswer;
  medicines: MedicineAnswer;
  reason: ReasonAnswer;
  fromCurrentAppointment?: boolean;
}

/** What the patient is told, never the raw server code. */
export type Failure =
  | "invalid"
  | "closed"
  | "mismatch"
  | "locked"
  | "badForm"
  | "scanUnreadable"
  | "scanLimit"
  | "busy"
  | "error";

export class VisitError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly attemptsLeft?: number;
  constructor(status: number, code?: string, attemptsLeft?: number) {
    super(code ?? `HTTP ${status}`);
    this.status = status;
    this.code = code;
    this.attemptsLeft = attemptsLeft;
  }
  get failure(): Failure {
    return failureOf(this);
  }
}

/** Maps a server problem to what the UI does about it. */
export function failureOf(e: { status: number; code?: string }): Failure {
  switch (e.code) {
    case "DENTAL_VISIT_CLOSED":
      return "closed";
    case "DENTAL_CHECK_FAILED":
      return "mismatch";
    case "DENTAL_CHECK_LOCKED":
      return "locked";
    case "DENTAL_INVALID_FORM":
    case "BAD_REQUEST":
      return "badForm";
    case "DENTAL_SCAN_UNREADABLE":
    case "DENTAL_SCAN_UNSUPPORTED":
      return "scanUnreadable";
    case "DENTAL_SCAN_LIMIT":
      return "scanLimit";
    case "DENTAL_SCAN_BUSY":
      return "busy";
    case "DENTAL_FIELD_UNREADABLE":
      return "error";
  }
  if (e.status === 429 || e.status === 503) return "busy";
  if (e.status === 404) return "invalid";
  if (e.status === 423) return "locked";
  return "error";
}

/** The i18n key for a failure. */
export const failureKey = (f: Failure): string => (f === "busy" || f === "error" ? `common.${f}` : `dental.err.${f}`);

// The token lives only in this module's link holder: never in storage or cookies.
let token: string | null = null;

/** Takes the token from the URL fragment the first time; later calls keep the one already held. */
export function bootstrapVisitToken(location: Location): boolean {
  const link = claimLink(location);
  if (link?.kind === "dental") token = link.token;
  return token !== null;
}
export function resetVisitToken(): void {
  token = null;
  resetLink();
}

async function call<T>(path: string, init: RequestInit = {}): Promise<{ status: number; data: T }> {
  const headers: Record<string, string> = { "X-Visit-Token": token ?? "" };
  if (init.body && !(init.body instanceof FormData)) headers["Content-Type"] = "application/json";
  let res: Response;
  try {
    res = await fetch(BASE + path, { ...init, headers, cache: "no-store", credentials: "omit" });
  } catch {
    throw new VisitError(0);
  }
  if (!res.ok) {
    let code: string | undefined;
    let left: number | undefined;
    try {
      const body = await res.json();
      code = typeof body.code === "string" ? body.code : undefined;
      if (typeof body.attemptsLeft === "number") left = body.attemptsLeft;
    } catch {
      /* not JSON */
    }
    throw new VisitError(res.status, code, left);
  }
  let data: T = undefined as T;
  try {
    data = (await res.json()).data as T;
  } catch {
    /* empty body (the form answers 201/200 with none) */
  }
  return { status: res.status, data };
}

export const getSummary = async (): Promise<VisitSummary> => (await call<VisitSummary>("")).data;

export function uploadScan(image: Blob): Promise<ScanResult> {
  const form = new FormData();
  form.append("image", image, "id.jpg");
  return call<ScanResult>("/id-scans", { method: "POST", body: form }).then((r) => r.data);
}

export async function submitForm(form: VisitForm): Promise<void> {
  await call<void>("/form", { method: "POST", body: JSON.stringify(form) });
}

/** The earlier answers behind the date-of-birth check; null when the server has none to give (404). */
export async function checkPrevious(dateOfBirth: string): Promise<PreviousAnswers | null> {
  try {
    return (await call<PreviousAnswers>("/previous", { method: "POST", body: JSON.stringify({ dateOfBirth }) })).data;
  } catch (err) {
    if (err instanceof VisitError && err.status === 404) return null;
    throw err;
  }
}

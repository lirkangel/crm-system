import type { PreviousAnswers, VisitForm, VisitIdentity } from "@/api/visit";

export const ALLERGY_ITEMS = ["PENICILLIN", "OTHER_ANTIBIOTIC", "LOCAL_ANAESTHETIC", "LATEX", "OTHER"] as const;
export const REASON_CODES = ["CHECKUP", "PAIN", "CLEANING", "OTHER"] as const;

export type Choice = "unset" | "none" | "some";

export interface IdentityState {
  idType: VisitIdentity["idType"];
  idNumber: string;
  fullName: string;
  dateOfBirth: string;
  nationality: string;
  address: string;
  idScanId: string | null;
}
export interface FormState {
  identity: IdentityState;
  parentName: string;
  parentPhone: string;
  phone: string;
  allergies: { choice: Choice; items: string[]; otherText: string };
  medicines: { choice: Choice; text: string };
  reason: { code: string; text: string };
  consent: boolean;
}

export const emptyForm = (): FormState => ({
  identity: { idType: "CCCD", idNumber: "", fullName: "", dateOfBirth: "", nationality: "VN", address: "", idScanId: null },
  parentName: "",
  parentPhone: "",
  phone: "",
  allergies: { choice: "unset", items: [], otherText: "" },
  medicines: { choice: "unset", text: "" },
  reason: { code: "", text: "" },
  consent: false,
});

const blank = (s: string) => s.trim() === "";

/** A Vietnamese mobile number as people type it (spaces, dots, dashes, +84). The server stays authoritative. */
export const phoneValid = (p: string) => /^(\+84|84|0)\d{9}$/.test(p.replace(/[\s.-]/g, ""));

export function identityBlank(i: IdentityState): boolean {
  return blank(i.idNumber) && blank(i.fullName) && i.dateOfBirth === "" && blank(i.address) && i.idScanId === null;
}
export function identityComplete(i: IdentityState): boolean {
  return (
    !blank(i.idNumber) &&
    !blank(i.fullName) &&
    i.dateOfBirth !== "" &&
    i.nationality !== "" &&
    (i.nationality !== "VN" || !blank(i.address))
  );
}
/** An adult must give an identity; a child may skip it, but not half-fill it. */
export function identityStepValid(f: FormState, child: boolean): boolean {
  if (!child) return identityComplete(f.identity);
  const parentOk = !blank(f.parentName) && phoneValid(f.parentPhone);
  return parentOk && (identityBlank(f.identity) || identityComplete(f.identity));
}

export function allergiesAnswered(a: FormState["allergies"]): boolean {
  if (a.choice === "none") return true;
  if (a.choice !== "some" || a.items.length === 0) return false;
  return !a.items.includes("OTHER") || !blank(a.otherText);
}
export function medicinesAnswered(m: FormState["medicines"]): boolean {
  return m.choice === "none" || (m.choice === "some" && !blank(m.text));
}
export function reasonAnswered(r: FormState["reason"]): boolean {
  return r.code !== "" && (r.code !== "OTHER" || !blank(r.text));
}

/** Free text goes out as typed (line breaks included); text that is only blanks is not an answer. */
const freeText = (s: string): string | null => (blank(s) ? null : s);

/** Builds the request. Throws when a question is unanswered, so a blank can never go out as "none". */
export function buildPayload(f: FormState, child: boolean): VisitForm {
  if (!allergiesAnswered(f.allergies) || !medicinesAnswered(f.medicines) || !reasonAnswered(f.reason)) {
    throw new Error("incomplete answers");
  }
  const i = f.identity;
  const identity: VisitIdentity | null =
    child && identityBlank(i)
      ? null
      : {
          idType: i.idType,
          idNumber: i.idNumber.trim(),
          fullName: i.fullName.trim(),
          dateOfBirth: i.dateOfBirth,
          nationality: i.nationality,
          address: blank(i.address) ? null : i.address.trim(),
        };
  const allergies =
    f.allergies.choice === "none"
      ? { none: true, items: [], otherText: null }
      : {
          none: false,
          items: f.allergies.items,
          otherText: f.allergies.items.includes("OTHER") ? freeText(f.allergies.otherText) : null,
        };
  const medicines =
    f.medicines.choice === "none"
      ? { none: true, text: null }
      : { none: false, text: freeText(f.medicines.text) };
  return {
    identity,
    ...(child ? { parentName: f.parentName.trim(), parentPhone: f.parentPhone.trim() } : {}),
    phone: f.phone.trim(),
    allergies,
    medicines,
    reason: { code: f.reason.code, text: freeText(f.reason.text) },
    consent: true,
    idScanId: i.idScanId,
  };
}

/** Earlier answers fill allergies, medicines and reason only; identity never comes back. */
export function applyPrevious(f: FormState, p: PreviousAnswers): FormState {
  const a = p.allergies;
  const m = p.medicines;
  return {
    ...f,
    allergies: a.none
      ? { choice: "none", items: [], otherText: "" }
      : { choice: "some", items: a.items ?? [], otherText: a.otherText ?? "" },
    medicines: m.none ? { choice: "none", text: "" } : { choice: "some", text: m.text ?? "" },
    reason: { code: p.reason.code, text: p.reason.text ?? "" },
  };
}

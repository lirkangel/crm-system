import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { ApiError, type Identity } from "@/api/checkin";
import { countries } from "@/lib/countries";
import { ConsentNotice } from "./ConsentNotice";

const input = "mt-1 block min-h-11 w-full rounded-lg border border-slate-300 bg-white px-3 text-base";

function Field({ id, label, children }: { id: string; label: string; children: React.ReactNode }) {
  return (
    <div>
      <label htmlFor={id} className="text-sm font-medium">
        {label}
      </label>
      {children}
    </div>
  );
}

const today = () => new Date().toISOString().slice(0, 10);

/**
 * The editable identity fields plus consent and Send. Client hints mirror the server's rules (a Vietnamese citizen
 * needs an address, an entry date is not in the future; a typed passport also asks for visa and entry date); the
 * server stays authoritative.
 */
export function IdentityForm({
  initial,
  passport,
  submit,
}: {
  initial: Partial<Identity>;
  passport: boolean;
  /** Resolves when sent; rejects with ApiError to show a message here. */
  submit: (identity: Identity) => Promise<void>;
}) {
  const { t, i18n } = useTranslation();
  const [v, setV] = useState({
    idNumber: initial.idNumber ?? "",
    fullName: initial.fullName ?? "",
    dateOfBirth: initial.dateOfBirth ?? "",
    nationality: initial.nationality ?? (passport ? "" : "VN"),
    address: initial.address ?? "",
    visaNumber: initial.visaNumber ?? "",
    entryDate: initial.entryDate ?? "",
  });
  const [consent, setConsent] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const list = useMemo(() => countries(i18n.language), [i18n.language]);
  const set = (k: keyof typeof v) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setV((o) => ({ ...o, [k]: e.target.value }));

  const isVn = v.nationality === "VN";
  const complete =
    v.idNumber.trim() !== "" &&
    v.fullName.trim() !== "" &&
    v.dateOfBirth !== "" &&
    v.nationality !== "" &&
    (!isVn || v.address.trim() !== "") &&
    (!passport || isVn || (v.visaNumber.trim() !== "" && v.entryDate !== ""));
  const canSend = complete && consent && !busy;

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!canSend) return;
    setBusy(true);
    setError(null);
    try {
      await submit({
        idType: passport ? "PASSPORT" : ((initial.idType ?? "CCCD") as Identity["idType"]),
        idNumber: v.idNumber.trim(),
        fullName: v.fullName.trim(),
        dateOfBirth: v.dateOfBirth,
        nationality: v.nationality,
        address: v.address.trim() === "" ? null : v.address.trim(),
        visaNumber: v.visaNumber.trim() === "" ? null : v.visaNumber.trim(),
        entryDate: v.entryDate === "" ? null : v.entryDate,
        idScanId: initial.idScanId ?? null,
      });
    } catch (err) {
      setError(err instanceof ApiError ? err.messageKey : "common.error");
      setBusy(false);
    }
  }

  const req = (ok: boolean) => ({ required: true, "aria-invalid": !ok && undefined });
  return (
    <form onSubmit={onSubmit} className="space-y-4" noValidate>
      <Field id="f-idNumber" label={t("field.idNumber")}>
        <input id="f-idNumber" className={input} value={v.idNumber} onChange={set("idNumber")} maxLength={32} autoComplete="off" {...req(true)} />
      </Field>
      <Field id="f-fullName" label={t("field.fullName")}>
        <input id="f-fullName" className={input} value={v.fullName} onChange={set("fullName")} maxLength={200} autoComplete="off" {...req(true)} />
      </Field>
      <Field id="f-dob" label={t("field.dateOfBirth")}>
        <input id="f-dob" type="date" className={input} value={v.dateOfBirth} onChange={set("dateOfBirth")} max={today()} {...req(true)} />
      </Field>
      <Field id="f-nat" label={t("field.nationality")}>
        <select id="f-nat" className={input} value={v.nationality} onChange={set("nationality")} required>
          <option value="" disabled />
          {list.map((c) => (
            <option key={c.code} value={c.code}>
              {c.name}
            </option>
          ))}
        </select>
      </Field>
      {(isVn || !passport) && (
        <Field id="f-address" label={t("field.address")}>
          <input id="f-address" className={input} value={v.address} onChange={set("address")} maxLength={500} autoComplete="off" required={isVn} />
        </Field>
      )}
      {passport && (
        <>
          <Field id="f-visa" label={t("field.visaNumber")}>
            <input id="f-visa" className={input} value={v.visaNumber} onChange={set("visaNumber")} maxLength={32} autoComplete="off" required={!isVn} />
          </Field>
          <Field id="f-entry" label={t("field.entryDate")}>
            <input id="f-entry" type="date" className={input} value={v.entryDate} onChange={set("entryDate")} max={today()} required={!isVn} />
          </Field>
        </>
      )}
      <ConsentNotice checked={consent} onChange={setConsent} />
      {error && (
        <p role="alert" className="rounded-lg bg-red-50 p-3 text-sm text-red-800">
          {t(error)}
        </p>
      )}
      <button
        type="submit"
        disabled={!canSend}
        className="min-h-12 w-full rounded-lg bg-blue-700 px-4 text-base font-semibold text-white disabled:bg-slate-300 disabled:text-slate-500"
      >
        {busy ? t("action.sending") : t("action.send")}
      </button>
    </form>
  );
}

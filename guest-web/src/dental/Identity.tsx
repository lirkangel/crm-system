import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { failureKey, uploadScan, VisitError } from "@/api/visit";
import { countries } from "@/lib/countries";
import { UndecodablePhotoError, toUploadJpeg } from "@/lib/photo";
import { identityStepValid, type FormState, type IdentityState } from "./model";
import { ErrorNote, Field, Nav, inputClass } from "./ui";

/**
 * Identity by CCCD photo (the fields fill in for checking) or typed. For a child under 14 it is optional, and the
 * parent's name and phone are asked instead; for an adult the parent fields do not exist.
 */
export function Identity({
  form,
  child,
  update,
  onNext,
  onBack,
  onFatal,
}: {
  form: FormState;
  child: boolean;
  update: (fn: (f: FormState) => FormState) => void;
  onNext: () => void;
  onBack: () => void;
  onFatal: (failure: "invalid" | "closed") => void;
}) {
  const { t, i18n } = useTranslation();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const list = useMemo(() => countries(i18n.language), [i18n.language]);
  const i = form.identity;
  const setId = (k: keyof IdentityState) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    update((f) => ({ ...f, identity: { ...f.identity, [k]: e.target.value } }));

  async function onFile(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = "";
    if (!file) return;
    setBusy(true);
    setError(null);
    try {
      const scan = await uploadScan(await toUploadJpeg(file));
      const s = scan.fields;
      update((f) => ({
        ...f,
        identity: {
          idType: (s.idType as IdentityState["idType"]) ?? "CCCD",
          idNumber: s.idNumber ?? "",
          fullName: s.fullName ?? "",
          dateOfBirth: s.dateOfBirth ?? "",
          nationality: s.nationality ?? "VN",
          address: s.address ?? "",
          idScanId: scan.scanId,
        },
      }));
    } catch (err) {
      if (err instanceof UndecodablePhotoError) setError("scan.unreadablePhoto");
      else {
        const failure = err instanceof VisitError ? err.failure : "error";
        if (failure === "closed" || failure === "invalid") onFatal(failure);
        else setError(failureKey(failure));
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="space-y-4">
      <h2 className="text-lg font-semibold">{t("dental.identity.title")}</h2>
      {child && <p className="text-sm text-slate-700">{t("dental.identity.childHint")}</p>}
      {child && (
        <>
          <Field id="d-parentName" label={t("dental.parentName")}>
            <input
              id="d-parentName"
              className={inputClass}
              value={form.parentName}
              maxLength={200}
              autoComplete="off"
              onChange={(e) => update((f) => ({ ...f, parentName: e.target.value }))}
            />
          </Field>
          <Field id="d-parentPhone" label={t("dental.parentPhone")}>
            <input
              id="d-parentPhone"
              type="tel"
              inputMode="tel"
              className={inputClass}
              value={form.parentPhone}
              maxLength={20}
              autoComplete="off"
              onChange={(e) => update((f) => ({ ...f, parentPhone: e.target.value }))}
            />
          </Field>
        </>
      )}
      <label className="flex min-h-12 w-full cursor-pointer items-center justify-center rounded-lg border border-blue-700 bg-white px-4 text-base font-semibold text-blue-700 has-[:disabled]:opacity-60 focus-within:ring-4 focus-within:ring-blue-300 focus-within:ring-offset-2">
        {busy ? t("adult.reading") : t("dental.takePhoto")}
        <input type="file" accept="image/*" capture="environment" className="sr-only" disabled={busy} onChange={onFile} />
      </label>
      <p className="text-sm text-slate-600">{t("dental.identity.orType")}</p>
      <ErrorNote messageKey={error} />
      <Field id="f-idNumber" label={t("field.idNumber")}>
        <input id="f-idNumber" className={inputClass} value={i.idNumber} onChange={setId("idNumber")} maxLength={32} autoComplete="off" />
      </Field>
      <Field id="f-fullName" label={t("field.fullName")}>
        <input id="f-fullName" className={inputClass} value={i.fullName} onChange={setId("fullName")} maxLength={200} autoComplete="off" />
      </Field>
      <Field id="f-dob" label={t("field.dateOfBirth")}>
        <input id="f-dob" type="date" className={inputClass} value={i.dateOfBirth} onChange={setId("dateOfBirth")} />
      </Field>
      <Field id="f-nat" label={t("field.nationality")}>
        <select id="f-nat" className={inputClass} value={i.nationality} onChange={setId("nationality")}>
          <option value="" disabled />
          {list.map((c) => (
            <option key={c.code} value={c.code}>
              {c.name}
            </option>
          ))}
        </select>
      </Field>
      <Field id="f-address" label={t("field.address")}>
        <input id="f-address" className={inputClass} value={i.address} onChange={setId("address")} maxLength={500} autoComplete="off" />
      </Field>
      <Nav onNext={onNext} onBack={onBack} canNext={!busy && identityStepValid(form, child)} />
    </section>
  );
}

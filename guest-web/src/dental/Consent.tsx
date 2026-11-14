import { useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import type { FormState } from "./model";
import { ErrorNote } from "./ui";

// TODO(legal): Mathisterben to review before go-live.
// The consent wording lives in i18n key `dental.consent.text` (vi.json / en.json). It names the health data collected
// (allergies, medicines, reason for the visit), the identity details and CCCD photo, and says the photo is deleted
// within 30 days. The server stores consent_at and notice_version "2026-11-14" with the form. How long the form
// itself is kept is also for the legal review.
export function Consent({
  form,
  update,
  error,
  onSend,
  onBack,
  onIdentity,
}: {
  form: FormState;
  update: (fn: (f: FormState) => FormState) => void;
  error: string | null;
  /** Resolves when the attempt has ended, whatever the outcome; the app shows any error. */
  onSend: () => Promise<void>;
  onBack: () => void;
  /** Jump to the identity step (offered with the "check your details" message). */
  onIdentity: () => void;
}) {
  const { t } = useTranslation();
  const [busy, setBusy] = useState(false);
  const inFlight = useRef(false);
  const canSend = form.consent && !busy;

  async function send(e: React.FormEvent) {
    e.preventDefault();
    if (!canSend || inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    try {
      await onSend();
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  return (
    <form onSubmit={send} className="space-y-4" noValidate>
      <h2 className="text-lg font-semibold">{t("dental.consent.title")}</h2>
      <div className="rounded-lg border border-slate-300 bg-white p-4">
        <p className="text-sm leading-relaxed text-slate-700">{t("dental.consent.text")}</p>
        <label className="mt-3 flex min-h-11 items-center gap-3 text-sm font-medium">
          <input
            type="checkbox"
            className="size-6 shrink-0"
            checked={form.consent}
            onChange={(e) => update((f) => ({ ...f, consent: e.target.checked }))}
          />
          <span>{t("dental.consent.label")}</span>
        </label>
      </div>
      <ErrorNote messageKey={error} />
      {error === "dental.err.badForm" && (
        <button type="button" onClick={onIdentity} className="min-h-11 w-full rounded-lg border border-slate-300 bg-white px-4 text-base">
          {t("dental.backToIdentity")}
        </button>
      )}
      <button
        type="submit"
        disabled={!canSend}
        className="min-h-12 w-full rounded-lg bg-blue-700 px-4 text-base font-semibold text-white disabled:bg-slate-300 disabled:text-slate-500"
      >
        {busy ? t("action.sending") : t("action.send")}
      </button>
      <button type="button" onClick={onBack} className="min-h-11 w-full text-sm text-slate-600">
        {t("dental.back")}
      </button>
    </form>
  );
}

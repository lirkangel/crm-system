import { useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { checkPrevious, failureKey, VisitError, type PreviousAnswers } from "@/api/visit";
import { Field, inputClass } from "./ui";

const pad = (n: number) => String(n).padStart(2, "0");
const today = () => {
  const d = new Date();
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
};

/**
 * "Is this your date of birth?" for a returning patient. A match brings back the earlier allergies, medicines and
 * reason (never identity). The server counts wrong tries and locks after five; the form then starts empty.
 */
export function DobCheck({
  onResult,
  onSkip,
  onLocked,
  onFatal,
}: {
  /** The earlier answers, or null when the server had none to give. */
  onResult: (previous: PreviousAnswers | null) => void;
  onSkip: () => void;
  onLocked: () => void;
  onFatal: (failure: "invalid" | "closed") => void;
}) {
  const { t } = useTranslation();
  const [dob, setDob] = useState("");
  const [busy, setBusy] = useState(false);
  const inFlight = useRef(false);
  const [error, setError] = useState<{ key: string; count?: number } | null>(null);

  async function check(e: React.FormEvent) {
    e.preventDefault();
    if (dob === "" || inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setError(null);
    try {
      onResult(await checkPrevious(dob));
    } catch (err) {
      const failure = err instanceof VisitError ? err.failure : "error";
      if (failure === "locked") onLocked();
      else if (failure === "closed" || failure === "invalid") onFatal(failure);
      else if (failure === "mismatch") {
        const left = err instanceof VisitError ? err.attemptsLeft : undefined;
        setError(left === undefined ? { key: "dental.err.mismatch" } : { key: "dental.err.mismatchLeft", count: left });
      } else setError({ key: failureKey(failure) });
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  return (
    <form onSubmit={check} className="space-y-4" noValidate>
      <h2 className="text-lg font-semibold">{t("dental.dob.title")}</h2>
      <p className="text-sm text-slate-700">{t("dental.dob.hint")}</p>
      <Field id="d-dob" label={t("dental.dob.label")}>
        <input id="d-dob" type="date" className={inputClass} value={dob} max={today()} onChange={(e) => setDob(e.target.value)} />
      </Field>
      {error && (
        <p role="alert" className="rounded-lg bg-red-50 p-3 text-sm text-red-800">
          {t(error.key, { count: error.count })}
        </p>
      )}
      <button
        type="submit"
        disabled={dob === "" || busy}
        className="min-h-12 w-full rounded-lg bg-blue-700 px-4 text-base font-semibold text-white disabled:bg-slate-300 disabled:text-slate-500"
      >
        {t("dental.dob.check")}
      </button>
      <button type="button" onClick={onSkip} className="min-h-11 w-full text-sm text-slate-600">
        {t("dental.dob.skip")}
      </button>
    </form>
  );
}


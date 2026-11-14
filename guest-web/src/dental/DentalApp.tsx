import { useCallback, useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import {
  bootstrapVisitToken,
  failureKey,
  getSummary,
  submitForm,
  VisitError,
  type PreviousAnswers,
  type VisitSummary,
} from "@/api/visit";
import { ErrorBoundary } from "@/components/ErrorBoundary";
import { Allergies } from "./Allergies";
import { Closed, Invalid } from "./Closed";
import { Consent } from "./Consent";
import { DobCheck } from "./DobCheck";
import { Done } from "./Done";
import { Identity } from "./Identity";
import { Medicines } from "./Medicines";
import { Phone } from "./Phone";
import { Reason } from "./Reason";
import { Summary } from "./Summary";
import { applyPrevious, buildPayload, emptyForm, type FormState } from "./model";

type Step = "summary" | "dob" | "identity" | "phone" | "allergies" | "medicines" | "reason" | "consent" | "done";
type View = { name: "loading" } | { name: "invalid" } | { name: "closed" } | { name: "failed"; key: string } | { name: "flow" };

/** The dental pre-visit flow, opened from `#d/<token>`. Answers live in memory only. */
export function DentalApp() {
  const { t, i18n } = useTranslation();
  const [view, setView] = useState<View>({ name: "loading" });
  const [summary, setSummary] = useState<VisitSummary | null>(null);
  const [step, setStep] = useState<Step>("summary");
  const [form, setForm] = useState<FormState>(emptyForm);
  const [locked, setLocked] = useState(false);
  const [sendError, setSendError] = useState<string | null>(null);
  const loading = useRef(false);

  useEffect(() => {
    document.documentElement.lang = i18n.language;
    document.title = t("dental.docTitle");
  }, [i18n.language, t]);

  const fatal = useCallback((failure: "invalid" | "closed") => {
    setView(failure === "closed" ? { name: "closed" } : { name: "invalid" });
  }, []);

  const load = useCallback(async () => {
    if (loading.current) return;
    loading.current = true;
    setView({ name: "loading" });
    try {
      if (!bootstrapVisitToken(window.location)) return setView({ name: "invalid" });
      const s = await getSummary();
      setSummary(s);
      setLocked(s.returning === "LOCKED");
      setView(s.open ? { name: "flow" } : { name: "closed" });
    } catch (err) {
      const failure = err instanceof VisitError ? err.failure : "error";
      if (failure === "invalid" || failure === "closed") fatal(failure);
      else setView({ name: "failed", key: failureKey(failure) });
    } finally {
      loading.current = false;
    }
  }, [fatal]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load();
  }, [load]);

  const update = (fn: (f: FormState) => FormState) => setForm(fn);
  const child = summary?.child ?? false;

  function start() {
    setStep(summary?.returning === "AVAILABLE" && !locked ? "dob" : "identity");
  }
  function onPrevious(p: PreviousAnswers | null) {
    if (p) setForm((f) => applyPrevious(f, p));
    setStep("identity");
  }
  function onLocked() {
    setLocked(true);
    setStep("identity");
  }

  async function send() {
    setSendError(null);
    try {
      await submitForm(buildPayload(form, child));
      setStep("done");
    } catch (err) {
      const failure = err instanceof VisitError ? err.failure : "error";
      if (failure === "closed" || failure === "invalid") fatal(failure);
      else setSendError(failureKey(failure));
    }
  }

  const other = i18n.language === "vi" ? "en" : "vi";
  let body: React.ReactNode;
  if (view.name === "loading") body = <p>{t("common.loading")}</p>;
  else if (view.name === "invalid") body = <Invalid />;
  else if (view.name === "closed") body = <Closed />;
  else if (view.name === "failed")
    body = (
      <div className="space-y-3">
        <p role="alert">{t(view.key)}</p>
        <button type="button" className="min-h-11 rounded-lg border bg-white px-4" onClick={() => void load()}>
          {t("common.retry")}
        </button>
      </div>
    );
  else if (summary) {
    const to = (s: Step) => () => setStep(s);
    const notice = locked && step === "identity" ? <p className="mb-4 text-sm text-slate-600">{t("dental.dob.locked")}</p> : null;
    switch (step) {
      case "summary":
        body = <Summary summary={summary} locked={locked} onStart={start} />;
        break;
      case "dob":
        body = <DobCheck onResult={onPrevious} onSkip={to("identity")} onLocked={onLocked} onFatal={fatal} />;
        break;
      case "identity":
        body = (
          <>
            {notice}
            <Identity form={form} child={child} update={update} onNext={to("phone")} onBack={to("summary")} onFatal={fatal} />
          </>
        );
        break;
      case "phone":
        body = <Phone form={form} update={update} onNext={to("allergies")} onBack={to("identity")} />;
        break;
      case "allergies":
        body = <Allergies form={form} update={update} onNext={to("medicines")} onBack={to("phone")} />;
        break;
      case "medicines":
        body = <Medicines form={form} update={update} onNext={to("reason")} onBack={to("allergies")} />;
        break;
      case "reason":
        body = <Reason form={form} update={update} onNext={to("consent")} onBack={to("medicines")} />;
        break;
      case "consent":
        body = <Consent form={form} update={update} error={sendError} onSend={send} onBack={to("reason")} />;
        break;
      case "done":
        body = <Done />;
        break;
    }
  }

  return (
    <div className="mx-auto min-h-screen max-w-md px-4 pb-10">
      <header className="flex items-center justify-between py-3">
        <h1 className="text-xl font-bold">{summary?.clinicName ?? t("dental.title")}</h1>
        <button
          type="button"
          className="min-h-11 min-w-11 rounded-lg border border-slate-300 bg-white px-3 text-sm"
          onClick={() => void i18n.changeLanguage(other)}
        >
          {t("lang.switch")}
        </button>
      </header>
      <ErrorBoundary>
        <main>{body}</main>
      </ErrorBoundary>
    </div>
  );
}

import { useCallback, useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import {
  ApiError,
  bootstrapToken,
  deleteGuest,
  getSummary,
  submitGuest,
  type Identity,
  type ScanResult,
  type Summary,
} from "@/api/checkin";
import { ErrorBoundary } from "@/components/ErrorBoundary";
import { AdultStep } from "@/screens/AdultStep";
import { Closed } from "@/screens/Closed";
import { Invalid } from "@/screens/Invalid";
import { PassportForm } from "@/screens/PassportForm";
import { ScanReview } from "@/screens/ScanReview";
import { Stay } from "@/screens/Stay";

type View =
  | { name: "loading" }
  | { name: "invalid" }
  | { name: "closed" }
  | { name: "failed"; key: string }
  | { name: "stay" }
  | { name: "adult" }
  | { name: "passport" }
  | { name: "review"; scan: ScanResult };

export default function App() {
  const { t, i18n } = useTranslation();
  const [view, setView] = useState<View>({ name: "loading" });
  const [summary, setSummary] = useState<Summary | null>(null);
  const [stayError, setStayError] = useState<string | null>(null);

  useEffect(() => {
    document.documentElement.lang = i18n.language;
  }, [i18n.language]);

  const fatal = useCallback((key: string) => {
    setView(key === "closed" ? { name: "closed" } : { name: "invalid" });
  }, []);

  const load = useCallback(async () => {
    if (!bootstrapToken(window.location)) return setView({ name: "invalid" });
    try {
      const s = await getSummary();
      setSummary(s);
      setView(s.open ? { name: "stay" } : { name: "closed" });
    } catch (err) {
      const key = err instanceof ApiError ? err.messageKey : "common.error";
      if (key === "invalid" || key === "closed") fatal(key);
      else setView({ name: "failed", key });
    }
  }, [fatal]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load();
  }, [load]);

  async function submit(identity: Identity) {
    try {
      await submitGuest(identity);
    } catch (err) {
      if (err instanceof ApiError && (err.messageKey === "closed" || err.messageKey === "invalid")) {
        fatal(err.messageKey);
        return;
      }
      throw err;
    }
    setStayError(null);
    await load();
  }

  async function remove(id: string) {
    setStayError(null);
    try {
      await deleteGuest(id);
      await load();
    } catch (err) {
      const key = err instanceof ApiError ? err.messageKey : "common.error";
      if (key === "closed" || key === "invalid") fatal(key);
      else setStayError(key);
    }
  }

  const toStay = () => setView({ name: "stay" });
  const other = i18n.language === "vi" ? "en" : "vi";

  let body: React.ReactNode;
  switch (view.name) {
    case "loading":
      body = <p>{t("common.loading")}</p>;
      break;
    case "invalid":
      body = <Invalid />;
      break;
    case "closed":
      body = <Closed />;
      break;
    case "failed":
      body = (
        <div className="space-y-3">
          <p role="alert">{t(view.key)}</p>
          <button type="button" className="min-h-11 rounded-lg border bg-white px-4" onClick={() => void load()}>
            {t("common.retry")}
          </button>
        </div>
      );
      break;
    case "stay":
      body = summary && (
        <Stay summary={summary} error={stayError} onAdd={() => setView({ name: "adult" })} onRemove={(id) => void remove(id)} />
      );
      break;
    case "adult":
      body = (
        <AdultStep
          onScanned={(scan) => setView({ name: "review", scan })}
          onPassport={() => setView({ name: "passport" })}
          onBack={toStay}
          onFatal={fatal}
        />
      );
      break;
    case "passport":
      body = <PassportForm submit={submit} onBack={() => setView({ name: "adult" })} />;
      break;
    case "review":
      body = <ScanReview scan={view.scan} submit={submit} onBack={() => setView({ name: "adult" })} />;
      break;
  }

  return (
    <div className="mx-auto min-h-screen max-w-md px-4 pb-10">
      <header className="flex items-center justify-between py-3">
        <h1 className="text-xl font-bold">{t("app.title")}</h1>
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

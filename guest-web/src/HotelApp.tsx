import { useCallback, useEffect, useRef, useState } from "react";
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

export default function HotelApp() {
  const { t, i18n } = useTranslation();
  const [view, setView] = useState<View>({ name: "loading" });
  const [summary, setSummary] = useState<Summary | null>(null);
  const [stayError, setStayError] = useState<string | null>(null);
  const [stayNotice, setStayNotice] = useState<string | null>(null);
  const [removing, setRemoving] = useState(false);
  const removingRef = useRef(false);

  useEffect(() => {
    document.documentElement.lang = i18n.language;
    document.title = t("app.docTitle");
  }, [i18n.language, t]);

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
    let created: boolean;
    try {
      created = (await submitGuest(identity)).created;
    } catch (err) {
      if (err instanceof ApiError && (err.messageKey === "closed" || err.messageKey === "invalid")) {
        fatal(err.messageKey);
        return;
      }
      throw err;
    }
    setStayError(null);
    // The same document sent again is answered with the stored submission (200), not a new one (201)
    setStayNotice(created ? null : "guests.alreadySent");
    await load();
  }

  async function remove(id: string) {
    if (removingRef.current) return;
    removingRef.current = true;
    setRemoving(true);
    setStayError(null);
    setStayNotice(null);
    try {
      await deleteGuest(id);
      await load();
    } catch (err) {
      if (err instanceof ApiError && err.status === 404) {
        // Already gone (another tap, another device) or the link died: the summary tells which. Only a 404 on the
        // summary itself makes the link invalid.
        await load();
      } else {
        const key = err instanceof ApiError ? err.messageKey : "common.error";
        if (key === "closed") fatal(key);
        else setStayError(key);
      }
    } finally {
      removingRef.current = false;
      setRemoving(false);
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
        <Stay
          summary={summary}
          error={stayError}
          notice={stayNotice}
          removing={removing}
          onAdd={() => {
            setStayNotice(null);
            setView({ name: "adult" });
          }}
          onRemove={(id) => void remove(id)}
        />
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

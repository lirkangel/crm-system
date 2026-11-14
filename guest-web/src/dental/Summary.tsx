import { useTranslation } from "react-i18next";
import type { VisitSummary } from "@/api/visit";

function when(iso: string, lang: string): string {
  try {
    return new Intl.DateTimeFormat(lang === "vi" ? "vi-VN" : "en-GB", {
      dateStyle: "full",
      timeStyle: "short",
      timeZone: "Asia/Ho_Chi_Minh",
    }).format(new Date(iso));
  } catch {
    return iso;
  }
}

export function Summary({ summary, locked, onStart }: { summary: VisitSummary; locked: boolean; onStart: () => void }) {
  const { t, i18n } = useTranslation();
  return (
    <section className="space-y-4">
      <div className="rounded-lg bg-white p-4">
        <h2 className="text-lg font-semibold">{t("dental.summary.title")}</h2>
        <p className="mt-1 text-sm text-slate-700">{when(summary.startsAt, i18n.language)}</p>
        {summary.dentist && (
          <p className="text-sm text-slate-700">
            {t("dental.summary.dentist")}: {summary.dentist}
          </p>
        )}
        <p className="mt-2 text-sm text-slate-700">
          {t("dental.summary.patient")}: <span className="font-medium">{summary.maskedName}</span>
        </p>
      </div>
      {summary.submitted && (
        <p role="status" className="rounded-lg border border-green-300 bg-green-50 p-3 text-sm text-green-900">
          {t("dental.summary.submitted")}
        </p>
      )}
      <p className="text-sm text-slate-700">{t("dental.summary.hint")}</p>
      {locked && <p className="text-sm text-slate-600">{t("dental.dob.locked")}</p>}
      <button type="button" onClick={onStart} className="min-h-12 w-full rounded-lg bg-blue-700 px-4 text-base font-semibold text-white">
        {summary.submitted ? t("dental.summary.update") : t("dental.start")}
      </button>
    </section>
  );
}

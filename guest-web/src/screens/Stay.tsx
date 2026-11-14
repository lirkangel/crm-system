import { useTranslation } from "react-i18next";
import type { Summary } from "@/api/checkin";
import { Done } from "./Done";

export function Stay({
  summary,
  onAdd,
  onRemove,
  error,
}: {
  summary: Summary;
  onAdd: () => void;
  onRemove: (id: string) => void;
  error: string | null;
}) {
  const { t } = useTranslation();
  const count = summary.guests.length;
  return (
    <section className="space-y-4">
      <div className="rounded-lg bg-white p-4">
        <h2 className="text-lg font-semibold">{summary.hotelName}</h2>
        <p className="mt-1 text-sm text-slate-700">
          {t("stay.room")}: {summary.roomTypeName}
        </p>
        <p className="text-sm text-slate-700">
          {t("stay.dates", { checkIn: summary.checkIn, checkOut: summary.checkOut })}
        </p>
        <p className="text-sm text-slate-700">
          {t("stay.adults")}: {summary.adults}
        </p>
      </div>

      {count >= summary.adults && <Done adults={summary.adults} />}

      <div className="rounded-lg bg-white p-4">
        <h3 className="font-semibold">{t("stay.submitted", { count, adults: summary.adults })}</h3>
        {count === 0 ? (
          <p className="mt-2 text-sm text-slate-600">{t("stay.none")}</p>
        ) : (
          <ul className="mt-2 divide-y divide-slate-200">
            {summary.guests.map((g) => (
              <li key={g.id} className="flex items-center justify-between gap-2 py-1">
                <span>
                  {g.maskedName} <span className="text-xs text-slate-500">({g.documentType})</span>
                </span>
                <button
                  type="button"
                  aria-label={t("stay.removeAria", { name: g.maskedName })}
                  onClick={() => onRemove(g.id)}
                  className="min-h-11 min-w-11 rounded-lg px-3 text-sm text-red-700"
                >
                  {t("stay.remove")}
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>

      {error && (
        <p role="alert" className="rounded-lg bg-red-50 p-3 text-sm text-red-800">
          {t(error)}
        </p>
      )}
      {count < summary.maxGuests && (
        <button
          type="button"
          onClick={onAdd}
          className="min-h-12 w-full rounded-lg bg-blue-700 px-4 text-base font-semibold text-white"
        >
          {count >= summary.adults ? t("stay.addAnother") : t("stay.add")}
        </button>
      )}
    </section>
  );
}

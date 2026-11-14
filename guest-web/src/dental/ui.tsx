import { useTranslation } from "react-i18next";

export const inputClass = "mt-1 block min-h-11 w-full rounded-lg border border-slate-300 bg-white px-3 text-base";

export function Field({ id, label, children }: { id: string; label: string; children: React.ReactNode }) {
  return (
    <div>
      <label htmlFor={id} className="text-sm font-medium">
        {label}
      </label>
      {children}
    </div>
  );
}

export function ErrorNote({ messageKey }: { messageKey: string | null }) {
  const { t } = useTranslation();
  if (!messageKey) return null;
  return (
    <p role="alert" className="rounded-lg bg-red-50 p-3 text-sm text-red-800">
      {t(messageKey)}
    </p>
  );
}

/** A radio row with a 44px touch target. */
export function Choice({
  name,
  label,
  checked,
  onChange,
  type = "radio",
}: {
  name: string;
  label: string;
  checked: boolean;
  onChange: () => void;
  type?: "radio" | "checkbox";
}) {
  return (
    <label className="flex min-h-11 items-center gap-3 rounded-lg border border-slate-300 bg-white px-3 text-base">
      <input type={type} name={name} className="size-5 shrink-0" checked={checked} onChange={onChange} />
      <span>{label}</span>
    </label>
  );
}

export function Nav({ onNext, onBack, canNext }: { onNext: () => void; onBack: () => void; canNext: boolean }) {
  const { t } = useTranslation();
  return (
    <div className="space-y-2 pt-2">
      <button
        type="button"
        disabled={!canNext}
        onClick={onNext}
        className="min-h-12 w-full rounded-lg bg-blue-700 px-4 text-base font-semibold text-white disabled:bg-slate-300 disabled:text-slate-500"
      >
        {t("dental.next")}
      </button>
      <button type="button" onClick={onBack} className="min-h-11 w-full text-sm text-slate-600">
        {t("dental.back")}
      </button>
    </div>
  );
}

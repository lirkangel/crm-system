import { useTranslation } from "react-i18next";

// TODO(legal): Mathisterben to review before go-live.
// The consent wording lives in i18n key `consent.text` (vi.json / en.json). It states what is collected (identity,
// CCCD photo), why (police registration as required by law), that the photo is deleted within 30 days, and that
// the sender may send the other adults' details. The server stores notice_version "2026-11-14" with the consent.
export function ConsentNotice({
  checked,
  onChange,
}: {
  checked: boolean;
  onChange: (checked: boolean) => void;
}) {
  const { t } = useTranslation();
  return (
    <div className="rounded-lg border border-slate-300 bg-white p-4">
      <p className="text-sm leading-relaxed text-slate-700">{t("consent.text")}</p>
      <label className="mt-3 flex min-h-11 items-center gap-3 text-sm font-medium">
        <input
          type="checkbox"
          className="size-6 shrink-0"
          checked={checked}
          onChange={(e) => onChange(e.target.checked)}
        />
        <span>{t("consent.label")}</span>
      </label>
    </div>
  );
}

import { useTranslation } from "react-i18next";

export function Done() {
  const { t } = useTranslation();
  return (
    <div role="status" className="rounded-lg border border-green-300 bg-green-50 p-4">
      <h2 className="font-semibold text-green-900">{t("dental.done.title")}</h2>
      <p className="mt-1 text-sm text-green-900">{t("dental.done.text")}</p>
    </div>
  );
}

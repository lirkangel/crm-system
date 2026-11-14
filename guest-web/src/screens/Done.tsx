import { useTranslation } from "react-i18next";

export function Done({ adults }: { adults: number }) {
  const { t } = useTranslation();
  return (
    <div role="status" className="rounded-lg border border-green-300 bg-green-50 p-4">
      <h3 className="font-semibold text-green-900">{t("done.title")}</h3>
      <p className="mt-1 text-sm text-green-900">{t("done.text", { adults })}</p>
    </div>
  );
}

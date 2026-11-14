import { useTranslation } from "react-i18next";

export function Closed() {
  const { t } = useTranslation();
  return (
    <section className="rounded-lg bg-white p-5 text-center">
      <h2 className="text-lg font-semibold">{t("dental.closedTitle")}</h2>
      <p className="mt-2">{t("dental.closed")}</p>
    </section>
  );
}

export function Invalid() {
  const { t } = useTranslation();
  return (
    <section className="rounded-lg bg-white p-5 text-center">
      <h2 className="text-lg font-semibold">{t("dental.invalidTitle")}</h2>
      <p className="mt-2">{t("dental.invalid")}</p>
    </section>
  );
}

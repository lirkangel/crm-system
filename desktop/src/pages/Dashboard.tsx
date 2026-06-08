import { useTranslation } from "react-i18next";

/** Authenticated landing page, rendered inside the persistent AppShell layout. */
export function Dashboard() {
  const { t } = useTranslation();

  return (
    <div className="flex flex-col items-center gap-2 p-12 text-center">
      <h1 className="text-3xl font-semibold tracking-tight">{t("dashboard.title")}</h1>
      <p className="text-muted-foreground">{t("dashboard.welcome")}</p>
    </div>
  );
}

export default Dashboard;

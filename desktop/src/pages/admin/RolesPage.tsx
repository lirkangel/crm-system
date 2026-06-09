import { useTranslation } from "react-i18next";

export function RolesPage() {
  const { t } = useTranslation();
  return (
    <div className="p-6">
      <h1 className="text-xl font-semibold">{t("nav.admin.roles")}</h1>
    </div>
  );
}

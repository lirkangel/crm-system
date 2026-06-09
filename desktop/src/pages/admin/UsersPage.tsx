import { useTranslation } from "react-i18next";

import { useUsers } from "./useUsers";

export function UsersPage() {
  const { t } = useTranslation();
  const { users, loading, error } = useUsers();

  return (
    <div className="p-6">
      <h1 className="mb-6 text-xl font-semibold">{t("admin.users.title")}</h1>

      {loading && (
        <div role="status" className="text-muted-foreground animate-pulse text-sm">
          {t("common.loading", "Đang tải…")}
        </div>
      )}

      {!loading && error && (
        <div role="alert" className="text-destructive rounded-md border p-4 text-sm">
          {t("admin.users.loadError")}
        </div>
      )}

      {!loading && !error && users.length === 0 && (
        <p className="text-muted-foreground text-sm">{t("admin.users.empty")}</p>
      )}

      {!loading && !error && users.length > 0 && (
        <div className="rounded-md border">
          <table className="w-full text-sm">
            <thead className="bg-muted/50">
              <tr>
                <th className="px-4 py-3 text-left font-medium">{t("admin.users.username")}</th>
                <th className="px-4 py-3 text-left font-medium">{t("admin.users.email")}</th>
                <th className="px-4 py-3 text-left font-medium">{t("admin.users.status")}</th>
              </tr>
            </thead>
            <tbody>
              {users.map((user) => (
                <tr key={user.id} className="border-t">
                  <td className="px-4 py-3 font-medium">{user.username}</td>
                  <td className="px-4 py-3 text-muted-foreground">{user.email}</td>
                  <td className="px-4 py-3">
                    <span
                      className={
                        user.enabled
                          ? "inline-flex items-center rounded-full bg-green-100 px-2 py-0.5 text-xs font-medium text-green-800"
                          : "inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-600"
                      }
                    >
                      {user.enabled ? t("admin.users.active") : t("admin.users.inactive")}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

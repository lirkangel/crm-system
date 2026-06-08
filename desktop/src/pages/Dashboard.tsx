import { LogOut } from "lucide-react";
import { useTranslation } from "react-i18next";

import { useAuth } from "@/auth/AuthContext";
import { Button } from "@/components/ui/button";

/**
 * Minimal authenticated landing — a placeholder destination for the route
 * guard. The real app shell (nav, header, sync pill) arrives in F701.
 */
export function Dashboard() {
  const { t } = useTranslation();
  const { logout } = useAuth();

  return (
    <main className="bg-background text-foreground flex min-h-screen flex-col items-center justify-center gap-6 p-8">
      <div className="flex flex-col items-center gap-2 text-center">
        <h1 className="text-3xl font-semibold tracking-tight">{t("dashboard.title")}</h1>
        <p className="text-muted-foreground">{t("dashboard.welcome")}</p>
      </div>
      <Button variant="outline" onClick={() => void logout()}>
        <LogOut />
        {t("common.logout")}
      </Button>
    </main>
  );
}

export default Dashboard;

import { LogOut } from "lucide-react";
import { useTranslation } from "react-i18next";
import { NavLink, Outlet } from "react-router";

import { useAuth } from "@/auth/AuthContext";
import { LanguageToggle } from "@/components/LanguageToggle";
import { SyncStatusPill } from "@/components/layout/SyncStatusPill";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

const navItems = [{ to: "/", labelKey: "nav.dashboard" }] as const;

/** Persistent authenticated shell: header (brand, nav, sync pill, user menu) wrapping routed pages. */
export function AppShell() {
  const { t } = useTranslation();
  const { logout } = useAuth();

  return (
    <div className="bg-background text-foreground flex min-h-screen flex-col">
      <header className="border-b">
        <div className="flex h-14 items-center gap-6 px-6">
          <span className="font-semibold tracking-tight">{t("app.title")}</span>
          <nav className="flex items-center gap-4 text-sm">
            {navItems.map(({ to, labelKey }) => (
              <NavLink
                key={to}
                to={to}
                end
                className={({ isActive }) =>
                  cn(
                    "text-muted-foreground hover:text-foreground transition-colors",
                    isActive && "text-foreground font-medium",
                  )
                }
              >
                {t(labelKey)}
              </NavLink>
            ))}
          </nav>
          <div className="ml-auto flex items-center gap-3">
            <SyncStatusPill />
            <LanguageToggle />
            <Button variant="outline" size="sm" onClick={() => void logout()}>
              <LogOut />
              {t("common.logout")}
            </Button>
          </div>
        </div>
      </header>
      <main className="flex-1">
        <Outlet />
      </main>
    </div>
  );
}

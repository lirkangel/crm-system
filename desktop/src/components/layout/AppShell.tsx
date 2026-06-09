import { LogOut } from "lucide-react";
import { useTranslation } from "react-i18next";
import { NavLink, Outlet } from "react-router";

import { useAuth } from "@/auth/AuthContext";
import { hasPermission } from "@/auth/permissions";
import { useCurrentUser } from "@/auth/useCurrentUser";
import { LanguageToggle } from "@/components/LanguageToggle";
import { SyncStatusPill } from "@/components/layout/SyncStatusPill";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

interface NavItem {
  to: string;
  labelKey: string;
  end?: boolean;
  permission?: string;
}

const navItems: NavItem[] = [
  { to: "/", labelKey: "nav.dashboard", end: true },
  { to: "/admin/users", labelKey: "nav.admin.users", permission: "core.users.read" },
  { to: "/admin/roles", labelKey: "nav.admin.roles", permission: "core.roles.read" },
];

/** Persistent authenticated shell: header (brand, nav, sync pill, user menu) wrapping routed pages. */
export function AppShell() {
  const { t } = useTranslation();
  const { logout } = useAuth();
  const { currentUser } = useCurrentUser();

  const visibleNavItems = navItems.filter(
    (item) => !item.permission || hasPermission(currentUser, item.permission),
  );

  return (
    <div className="bg-background text-foreground flex min-h-screen flex-col">
      <header className="border-b">
        <div className="flex h-14 items-center gap-6 px-6">
          <span className="font-semibold tracking-tight">{t("app.title")}</span>
          <nav className="flex items-center gap-4 text-sm">
            {visibleNavItems.map(({ to, labelKey, end }) => (
              <NavLink
                key={to}
                to={to}
                end={end}
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
            {currentUser && (
              <span
                data-testid="user-display"
                className="text-muted-foreground text-sm"
              >
                {currentUser.username}
              </span>
            )}
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

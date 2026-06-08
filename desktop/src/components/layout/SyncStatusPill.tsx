import { Check } from "lucide-react";
import { useTranslation } from "react-i18next";

/** Static "synced" placeholder; F501 wires this to live state from the Tauri sync engine. */
export function SyncStatusPill() {
  const { t } = useTranslation();

  return (
    <span className="text-muted-foreground inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs">
      <Check className="size-3.5" />
      {t("sync.synced")}
    </span>
  );
}

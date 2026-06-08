import { Languages } from "lucide-react";
import { useTranslation } from "react-i18next";

import { Button } from "@/components/ui/button";

/** Toggles between Vietnamese and English; labelled with the target language. */
export function LanguageToggle() {
  const { i18n, t } = useTranslation();
  const next = i18n.language.startsWith("vi") ? "en" : "vi";

  return (
    <Button variant="outline" size="sm" onClick={() => void i18n.changeLanguage(next)}>
      <Languages />
      {t(`language.${next}`)}
    </Button>
  );
}

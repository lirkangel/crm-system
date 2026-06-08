import i18n from "i18next";
import { initReactI18next } from "react-i18next";
import en from "./locales/en.json";
import vi from "./locales/vi.json";

export const defaultLocale = "vi";
export const supportedLocales = ["vi", "en"] as const;
export type Locale = (typeof supportedLocales)[number];

// Local bundles for now. When the backend i18n endpoint (B-EPIC-7) lands,
// swap in i18next-http-backend to load /api/i18n/<lang> — no component changes.
void i18n.use(initReactI18next).init({
  resources: {
    vi: { translation: vi },
    en: { translation: en },
  },
  lng: defaultLocale,
  fallbackLng: defaultLocale,
  supportedLngs: supportedLocales,
  interpolation: {
    escapeValue: false, // React already escapes values
  },
});

export default i18n;

import { useTranslation } from "react-i18next";
import type { Identity } from "@/api/checkin";
import { IdentityForm } from "@/components/IdentityForm";

export function PassportForm({
  submit,
  onBack,
}: {
  submit: (identity: Identity) => Promise<void>;
  onBack: () => void;
}) {
  const { t } = useTranslation();
  return (
    <section className="space-y-4">
      <h2 className="text-lg font-semibold">{t("passport.title")}</h2>
      <p className="text-sm text-slate-700">{t("passport.hint")}</p>
      <IdentityForm passport submit={submit} initial={{ idType: "PASSPORT" }} />
      <button type="button" onClick={onBack} className="min-h-11 w-full text-sm text-slate-600">
        {t("adult.back")}
      </button>
    </section>
  );
}

import { useTranslation } from "react-i18next";
import { phoneValid, type FormState } from "./model";
import { Field, Nav, inputClass } from "./ui";

export function Phone({
  form,
  update,
  onNext,
  onBack,
}: {
  form: FormState;
  update: (fn: (f: FormState) => FormState) => void;
  onNext: () => void;
  onBack: () => void;
}) {
  const { t } = useTranslation();
  return (
    <section className="space-y-4">
      <h2 className="text-lg font-semibold">{t("dental.phone")}</h2>
      <p className="text-sm text-slate-700">{t("dental.phoneHint")}</p>
      <Field id="d-phone" label={t("dental.phone")}>
        <input
          id="d-phone"
          type="tel"
          inputMode="tel"
          className={inputClass}
          value={form.phone}
          maxLength={20}
          autoComplete="tel"
          onChange={(e) => update((f) => ({ ...f, phone: e.target.value }))}
        />
      </Field>
      <Nav onNext={onNext} onBack={onBack} canNext={phoneValid(form.phone)} />
    </section>
  );
}

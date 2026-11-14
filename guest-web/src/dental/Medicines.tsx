import { useTranslation } from "react-i18next";
import { medicinesAnswered, type FormState } from "./model";
import { Choice, Field, Nav, inputClass } from "./ui";

export function Medicines({
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
  const m = form.medicines;
  const set = (patch: Partial<FormState["medicines"]>) => update((f) => ({ ...f, medicines: { ...f.medicines, ...patch } }));
  return (
    <section className="space-y-4">
      <h2 className="text-lg font-semibold">{t("dental.medicines.title")}</h2>
      <p className="text-sm text-slate-700">{t("dental.medicines.hint")}</p>
      <div className="space-y-2" role="radiogroup" aria-label={t("dental.medicines.title")}>
        <Choice name="medicines" label={t("dental.medicines.none")} checked={m.choice === "none"} onChange={() => set({ choice: "none" })} />
        <Choice name="medicines" label={t("dental.medicines.some")} checked={m.choice === "some"} onChange={() => set({ choice: "some" })} />
      </div>
      {m.choice === "some" && (
        <Field id="d-medText" label={t("dental.medicines.text")}>
          <textarea id="d-medText" className={inputClass + " min-h-28"} value={m.text} maxLength={1000} onChange={(e) => set({ text: e.target.value })} />
        </Field>
      )}
      <Nav onNext={onNext} onBack={onBack} canNext={medicinesAnswered(m)} />
    </section>
  );
}

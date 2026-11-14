import { useTranslation } from "react-i18next";
import { REASON_CODES, reasonAnswered, type FormState } from "./model";
import { Choice, Field, Nav, inputClass } from "./ui";

export function Reason({
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
  const r = form.reason;
  const set = (patch: Partial<FormState["reason"]>) => update((f) => ({ ...f, reason: { ...f.reason, ...patch } }));
  return (
    <section className="space-y-4">
      <h2 className="text-lg font-semibold">{t("dental.reason.title")}</h2>
      <div className="space-y-2" role="radiogroup" aria-label={t("dental.reason.title")}>
        {REASON_CODES.map((code) => (
          <Choice key={code} name="reason" label={t(`dental.reason.${code}`)} checked={r.code === code} onChange={() => set({ code })} />
        ))}
      </div>
      {r.code !== "" && (
        <Field id="d-reasonText" label={t(r.code === "OTHER" ? "dental.reason.text" : "dental.reason.textOptional")}>
          <textarea id="d-reasonText" className={inputClass + " min-h-24"} value={r.text} maxLength={500} onChange={(e) => set({ text: e.target.value })} />
        </Field>
      )}
      <Nav onNext={onNext} onBack={onBack} canNext={reasonAnswered(r)} />
    </section>
  );
}

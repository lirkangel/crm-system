import { useTranslation } from "react-i18next";
import { ALLERGY_ITEMS, allergiesAnswered, type FormState } from "./model";
import { Choice, Field, Nav, inputClass } from "./ui";

export function Allergies({
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
  const a = form.allergies;
  const set = (patch: Partial<FormState["allergies"]>) => update((f) => ({ ...f, allergies: { ...f.allergies, ...patch } }));
  const toggle = (code: string) =>
    set({ items: a.items.includes(code) ? a.items.filter((c) => c !== code) : [...a.items, code] });
  return (
    <section className="space-y-4">
      <h2 className="text-lg font-semibold">{t("dental.allergies.title")}</h2>
      <p className="text-sm text-slate-700">{t("dental.allergies.hint")}</p>
      <div className="space-y-2" role="radiogroup" aria-label={t("dental.allergies.title")}>
        <Choice name="allergies" label={t("dental.allergies.none")} checked={a.choice === "none"} onChange={() => set({ choice: "none" })} />
        <Choice name="allergies" label={t("dental.allergies.some")} checked={a.choice === "some"} onChange={() => set({ choice: "some" })} />
      </div>
      {a.choice === "some" && (
        <div className="space-y-2">
          {ALLERGY_ITEMS.map((code) => (
            <Choice key={code} type="checkbox" name="allergy" label={t(`dental.allergy.${code}`)} checked={a.items.includes(code)} onChange={() => toggle(code)} />
          ))}
          {a.items.includes("OTHER") && (
            <Field id="d-allergyText" label={t("dental.allergy.otherText")}>
              <textarea
                id="d-allergyText"
                className={inputClass + " min-h-24"}
                value={a.otherText}
                maxLength={500}
                onChange={(e) => set({ otherText: e.target.value })}
              />
            </Field>
          )}
        </div>
      )}
      <Nav onNext={onNext} onBack={onBack} canNext={allergiesAnswered(a)} />
    </section>
  );
}

import { useTranslation } from "react-i18next";
import type { Identity, ScanResult } from "@/api/checkin";
import { IdentityForm } from "@/components/IdentityForm";

export function ScanReview({
  scan,
  submit,
  onBack,
}: {
  scan: ScanResult;
  submit: (identity: Identity) => Promise<void>;
  onBack: () => void;
}) {
  const { t } = useTranslation();
  const f = scan.fields;
  return (
    <section className="space-y-4">
      <h2 className="text-lg font-semibold">{t("scan.reviewTitle")}</h2>
      <p className="text-sm text-slate-700">{t("scan.reviewHint")}</p>
      <IdentityForm
        passport={false}
        submit={submit}
        initial={{
          idType: (f.idType as Identity["idType"]) ?? "CCCD",
          idNumber: f.idNumber ?? "",
          fullName: f.fullName ?? "",
          dateOfBirth: f.dateOfBirth ?? "",
          nationality: f.nationality ?? "VN",
          address: f.address ?? "",
          idScanId: scan.scanId,
        }}
      />
      <button type="button" onClick={onBack} className="min-h-11 w-full text-sm text-slate-600">
        {t("adult.back")}
      </button>
    </section>
  );
}

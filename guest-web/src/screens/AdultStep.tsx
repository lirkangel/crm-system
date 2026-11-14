import { useState } from "react";
import { useTranslation } from "react-i18next";
import { ApiError, uploadScan, type ScanResult } from "@/api/checkin";
import { UndecodablePhotoError, toUploadJpeg } from "@/lib/photo";

export function AdultStep({
  onScanned,
  onPassport,
  onBack,
  onFatal,
}: {
  onScanned: (scan: ScanResult) => void;
  onPassport: () => void;
  onBack: () => void;
  /** Closed or invalid link while uploading. */
  onFatal: (key: string) => void;
}) {
  const { t } = useTranslation();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function onFile(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    e.target.value = "";
    if (!file) return;
    setBusy(true);
    setError(null);
    try {
      const jpeg = await toUploadJpeg(file);
      onScanned(await uploadScan(jpeg));
    } catch (err) {
      if (err instanceof UndecodablePhotoError) setError("scan.unreadablePhoto");
      else if (err instanceof ApiError) {
        if (err.messageKey === "closed" || err.messageKey === "invalid") onFatal(err.messageKey);
        else setError(err.messageKey);
      } else setError("common.error");
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="space-y-4">
      <h2 className="text-lg font-semibold">{t("adult.title")}</h2>
      <p className="text-sm text-slate-700">{t("adult.hint")}</p>
      <label className="flex min-h-12 w-full cursor-pointer items-center justify-center rounded-lg bg-blue-700 px-4 text-base font-semibold text-white has-[:disabled]:bg-slate-400 focus-within:ring-4 focus-within:ring-blue-300 focus-within:ring-offset-2">
        {busy ? t("adult.reading") : t("adult.takePhoto")}
        <input type="file" accept="image/*" capture="environment" className="sr-only" disabled={busy} onChange={onFile} />
      </label>
      {error && (
        <p role="alert" className="rounded-lg bg-red-50 p-3 text-sm text-red-800">
          {t(error)}
        </p>
      )}
      <button type="button" onClick={onPassport} className="min-h-12 w-full rounded-lg border border-slate-300 bg-white px-4 text-base">
        {t("adult.noCccd")}
      </button>
      <button type="button" onClick={onBack} className="min-h-11 w-full text-sm text-slate-600">
        {t("adult.back")}
      </button>
    </section>
  );
}

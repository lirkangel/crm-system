/** Splits an ISO calendar date (yyyy-mm-dd) without going through a Date, so no time zone can shift it. */
function parts(iso: string): [number, number, number] | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso);
  return m ? [Number(m[1]), Number(m[2]), Number(m[3])] : null;
}

/** dd/mm/yyyy for Vietnamese, the browser's locale default for English; anything unparseable is shown as it is. */
export function formatDate(iso: string, language: string): string {
  const p = parts(iso);
  if (!p) return iso;
  const [y, m, d] = p;
  if (language === "vi") return `${String(d).padStart(2, "0")}/${String(m).padStart(2, "0")}/${y}`;
  return new Date(y, m - 1, d).toLocaleDateString();
}

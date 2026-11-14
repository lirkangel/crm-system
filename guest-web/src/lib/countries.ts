// Common source countries of guests first, then the rest alphabetically by display name.
const COMMON = ["VN", "KR", "CN", "JP", "US", "RU", "GB", "FR", "DE", "AU", "TW", "TH", "SG", "MY", "IN", "CA"];
const OTHERS = [
  "AE", "AR", "AT", "BE", "BD", "BG", "BR", "BY", "CH", "CL", "CO", "CZ", "DK", "EG", "ES", "FI", "GR", "HK",
  "HR", "HU", "ID", "IE", "IL", "IT", "KH", "KZ", "LA", "LK", "MM", "MN", "MX", "NL", "NO", "NP", "NZ", "PE",
  "PH", "PK", "PL", "PT", "QA", "RO", "RS", "SA", "SE", "SK", "TR", "UA", "UZ", "ZA",
];

export interface Country {
  code: string;
  name: string;
}

export function countries(lang: string): Country[] {
  let names: Intl.DisplayNames | null = null;
  try {
    names = new Intl.DisplayNames([lang], { type: "region" });
  } catch {
    /* unsupported: fall back to codes */
  }
  const label = (code: string) => names?.of(code) ?? code;
  const rest = OTHERS.map((code) => ({ code, name: label(code) })).sort((a, b) =>
    a.name.localeCompare(b.name, lang),
  );
  return [...COMMON.map((code) => ({ code, name: label(code) })), ...rest];
}

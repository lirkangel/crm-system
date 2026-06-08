// Combining diacritical marks (U+0300–U+036F) left behind after NFD decomposition.
const COMBINING_MARKS = /[̀-ͯ]/g;

/**
 * Removes Vietnamese diacritics (tone + vowel marks) and maps đ/Đ to d/D.
 * Useful for diacritic-insensitive search, sorting, and slugs.
 * e.g. `"Phở Hà Nội"` → `"Pho Ha Noi"`.
 */
export function removeVietnameseTones(input: string): string {
  return input
    .normalize("NFD")
    .replace(COMBINING_MARKS, "")
    .replace(/đ/g, "d")
    .replace(/Đ/g, "D");
}

/**
 * Normalizes text for diacritic- and case-insensitive search:
 * lowercased, tone-stripped, and trimmed.
 */
export function normalizeForSearch(input: string): string {
  return removeVietnameseTones(input).toLowerCase().trim();
}

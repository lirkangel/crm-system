const vndGrouping = new Intl.NumberFormat("vi-VN", { maximumFractionDigits: 0 });

/**
 * Formats an amount as Vietnamese đồng, e.g. `1.500.000 ₫`.
 * VND has no minor unit in practice, so amounts are rounded to whole đồng.
 * Returns an empty string for non-finite input.
 */
export function formatVnd(amount: number): string {
  if (!Number.isFinite(amount)) {
    return "";
  }
  return `${vndGrouping.format(Math.round(amount))} ₫`;
}

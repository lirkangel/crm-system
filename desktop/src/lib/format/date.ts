export type DateInput = Date | string | number;

function toValidDate(input: DateInput): Date | null {
  const date = input instanceof Date ? input : new Date(input);
  return Number.isNaN(date.getTime()) ? null : date;
}

function pad2(value: number): string {
  return String(value).padStart(2, "0");
}

/**
 * Formats a date as `DD/MM/YYYY` (Vietnamese convention), using local time.
 * Returns an empty string for an invalid date.
 */
export function formatDateVn(input: DateInput): string {
  const date = toValidDate(input);
  if (!date) {
    return "";
  }
  return `${pad2(date.getDate())}/${pad2(date.getMonth() + 1)}/${date.getFullYear()}`;
}

/**
 * Formats a date as `DD/MM/YYYY HH:mm` in 24-hour local time.
 * Returns an empty string for an invalid date.
 */
export function formatDateTimeVn(input: DateInput): string {
  const date = toValidDate(input);
  if (!date) {
    return "";
  }
  return `${formatDateVn(date)} ${pad2(date.getHours())}:${pad2(date.getMinutes())}`;
}

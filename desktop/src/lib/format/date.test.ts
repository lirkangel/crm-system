import { describe, expect, it } from "vitest";

import { formatDateTimeVn, formatDateVn } from "./date";

describe("formatDateVn", () => {
  it("formats a Date as DD/MM/YYYY with zero padding", () => {
    expect(formatDateVn(new Date(2026, 5, 8))).toBe("08/06/2026");
  });

  it("accepts a millisecond timestamp", () => {
    expect(formatDateVn(new Date(2026, 11, 25).getTime())).toBe("25/12/2026");
  });

  it("returns empty string for an invalid date", () => {
    expect(formatDateVn("not-a-date")).toBe("");
  });
});

describe("formatDateTimeVn", () => {
  it("formats as DD/MM/YYYY HH:mm in 24-hour time", () => {
    expect(formatDateTimeVn(new Date(2026, 5, 8, 9, 5))).toBe("08/06/2026 09:05");
  });

  it("returns empty string for an invalid date", () => {
    expect(formatDateTimeVn("nope")).toBe("");
  });
});

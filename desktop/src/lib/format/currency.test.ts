import { describe, expect, it } from "vitest";

import { formatVnd } from "./currency";

describe("formatVnd", () => {
  it("formats with dot thousands separators and the đồng symbol", () => {
    expect(formatVnd(1500000)).toBe("1.500.000 ₫");
  });

  it("formats small amounts and zero", () => {
    expect(formatVnd(1234)).toBe("1.234 ₫");
    expect(formatVnd(0)).toBe("0 ₫");
  });

  it("rounds to whole đồng (no minor unit)", () => {
    expect(formatVnd(1500000.7)).toBe("1.500.001 ₫");
  });

  it("formats negative amounts", () => {
    expect(formatVnd(-1500000)).toBe("-1.500.000 ₫");
  });

  it("returns empty string for non-finite input", () => {
    expect(formatVnd(Number.NaN)).toBe("");
    expect(formatVnd(Number.POSITIVE_INFINITY)).toBe("");
  });
});

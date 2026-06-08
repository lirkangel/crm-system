import { describe, expect, it } from "vitest";

import { normalizeForSearch, removeVietnameseTones } from "./text";

describe("removeVietnameseTones", () => {
  it("strips Vietnamese diacritics", () => {
    expect(removeVietnameseTones("Tiếng Việt")).toBe("Tieng Viet");
    expect(removeVietnameseTones("Phở Hà Nội")).toBe("Pho Ha Noi");
  });

  it("converts đ/Đ to d/D", () => {
    expect(removeVietnameseTones("Đường")).toBe("Duong");
  });

  it("leaves plain ASCII untouched", () => {
    expect(removeVietnameseTones("Room 201")).toBe("Room 201");
  });

  it("handles an empty string", () => {
    expect(removeVietnameseTones("")).toBe("");
  });
});

describe("normalizeForSearch", () => {
  it("lowercases, strips tones, and trims for diacritic-insensitive search", () => {
    expect(normalizeForSearch("  Phở Hà Nội  ")).toBe("pho ha noi");
  });

  it("makes accented and unaccented queries comparable", () => {
    expect(normalizeForSearch("Việt")).toBe(normalizeForSearch("viet"));
  });
});

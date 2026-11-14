import { describe, expect, it } from "vitest";
import { buildPayload, emptyForm, phoneValid } from "./model";

describe("phoneValid", () => {
  it.each(["0912345678", "0912 345 678", "+84912345678", "84912345678", "091-234-5678"])("accepts %s", (p) =>
    expect(phoneValid(p)).toBe(true),
  );
  it.each(["", "12345", "091234567", "09123456789", "abc"])("rejects %s", (p) => expect(phoneValid(p)).toBe(false));
});

describe("buildPayload", () => {
  it("never sends none=false without items or text", () => {
    const f = emptyForm();
    f.allergies = { choice: "some", items: [], otherText: "" };
    f.medicines = { choice: "some", text: "  " };
    expect(() => buildPayload(f, false)).toThrow();
  });
});

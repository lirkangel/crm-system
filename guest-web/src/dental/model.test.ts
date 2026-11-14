import { describe, expect, it } from "vitest";
import { buildPayload, emptyForm, identityComplete, identityStepValid, localDate, phoneValid } from "./model";

describe("phoneValid", () => {
  it.each(["0912345678", "+84912345678", "0912-345.678", "0912 345 678", "0351234567"])("accepts %s", (p) =>
    expect(phoneValid(p)).toBe(true),
  );
  it.each(["", "12345", "84912345678", "0212345678", "0112345678", "091234567", "09123456789", "abc", "+840912345678"])(
    "rejects %s",
    (p) => expect(phoneValid(p)).toBe(false),
  );
});

describe("identity", () => {
  const base = { ...emptyForm().identity, idNumber: "1", fullName: "A", address: "x" };
  it("date of birth must be in the past", () => {
    expect(identityComplete({ ...base, dateOfBirth: localDate(1) })).toBe(true);
    expect(identityComplete({ ...base, dateOfBirth: localDate(0) })).toBe(false);
    expect(identityComplete({ ...base, dateOfBirth: localDate(-5) })).toBe(false);
  });
  it("child parent phone follows the same rule", () => {
    const f = { ...emptyForm(), parentName: "B" };
    expect(identityStepValid({ ...f, parentPhone: "0987654321" }, true)).toBe(true);
    for (const p of ["84987654321", "0287654321", "0187654321"]) {
      expect(identityStepValid({ ...f, parentPhone: p }, true)).toBe(false);
    }
  });
});

describe("buildPayload", () => {
  it("never sends none=false without items or text", () => {
    const f = emptyForm();
    f.allergies = { choice: "some", items: [], otherText: "" };
    f.medicines = { choice: "some", text: "  " };
    expect(() => buildPayload(f, false)).toThrow();
  });
});

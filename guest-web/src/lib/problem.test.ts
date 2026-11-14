import { describe, expect, it } from "vitest";
import { messageKey } from "./problem";

describe("messageKey", () => {
  it.each([
    [{ code: "HOTEL_SCAN_UNREADABLE", status: 400 }, "scan.unreadable"],
    [{ code: "HOTEL_SCAN_BUSY", status: 503 }, "common.busy"],
    [{ status: 429 }, "common.busy"],
    [{ code: "RATE_LIMITED", status: 429 }, "common.busy"],
    [{ code: "HOTEL_CHECKIN_CLOSED", status: 409 }, "closed"],
    [{ code: "HOTEL_NOT_FOUND", status: 404 }, "invalid"],
    [{ status: 404 }, "invalid"],
    [{ code: "HOTEL_TOO_MANY_GUESTS", status: 400 }, "adults.full"],
    [{ code: "HOTEL_SCAN_MISMATCH", status: 409 }, "scan.mismatch"],
    [{ code: "SOMETHING_ELSE", status: 500 }, "common.error"],
    [{ status: 500 }, "common.error"],
  ])("%j -> %s", (problem, key) => {
    expect(messageKey(problem)).toBe(key);
  });
});

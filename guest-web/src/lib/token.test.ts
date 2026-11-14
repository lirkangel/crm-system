import { describe, expect, it, vi } from "vitest";
import { readToken } from "./token";

describe("readToken", () => {
  it("reads the fragment and strips it from the address bar", () => {
    window.history.replaceState(null, "", "/x?a=1#abc_DEF-123");
    const spy = vi.spyOn(window.history, "replaceState");
    expect(readToken(window.location)).toBe("abc_DEF-123");
    expect(spy).toHaveBeenCalledWith(null, "", "/x?a=1");
    expect(window.location.hash).toBe("");
    expect(window.location.search).toBe("?a=1");
    spy.mockRestore();
  });

  it("returns null when there is no fragment", () => {
    window.history.replaceState(null, "", "/");
    expect(readToken(window.location)).toBeNull();
    window.history.replaceState(null, "", "/#");
    expect(readToken(window.location)).toBeNull();
  });
});

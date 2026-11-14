import { afterEach, describe, expect, it, vi } from "vitest";
import { claimLink, readToken, resetLink } from "./token";

afterEach(() => resetLink());

describe("readToken", () => {
  it("reads a hotel fragment and strips it from the address bar", () => {
    window.history.replaceState(null, "", "/x?a=1#abc_DEF-123");
    const spy = vi.spyOn(window.history, "replaceState");
    expect(readToken(window.location)).toEqual({ kind: "hotel", token: "abc_DEF-123" });
    expect(spy).toHaveBeenCalledWith(null, "", "/x?a=1");
    expect(window.location.hash).toBe("");
    expect(window.location.search).toBe("?a=1");
    spy.mockRestore();
  });

  it("reads a #d/ fragment as a dental link and strips it", () => {
    window.history.replaceState(null, "", "/x?a=1#d/abc_DEF-123");
    const spy = vi.spyOn(window.history, "replaceState");
    expect(readToken(window.location)).toEqual({ kind: "dental", token: "abc_DEF-123" });
    expect(spy).toHaveBeenCalledWith(null, "", "/x?a=1");
    expect(window.location.hash).toBe("");
    spy.mockRestore();
  });

  it("returns null when there is no fragment or no token after the prefix", () => {
    window.history.replaceState(null, "", "/");
    expect(readToken(window.location)).toBeNull();
    window.history.replaceState(null, "", "/#");
    expect(readToken(window.location)).toBeNull();
    window.history.replaceState(null, "", "/#d/");
    expect(readToken(window.location)).toBeNull();
    expect(window.location.hash).toBe("");
  });
});

describe("claimLink", () => {
  it("keeps the first link once the fragment is gone", () => {
    window.history.replaceState(null, "", "/#d/tok");
    expect(claimLink(window.location)).toEqual({ kind: "dental", token: "tok" });
    expect(claimLink(window.location)).toEqual({ kind: "dental", token: "tok" });
  });
});

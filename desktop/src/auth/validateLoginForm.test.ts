import { describe, expect, it } from "vitest";
import { validateLoginForm } from "./validateLoginForm";

describe("validateLoginForm", () => {
  it("returns no errors for a filled-in form", () => {
    expect(validateLoginForm({ username: "alice", password: "secret" })).toEqual({});
  });

  it("flags a missing username with an i18n key", () => {
    expect(validateLoginForm({ username: "", password: "secret" })).toEqual({
      username: "auth.errors.usernameRequired",
    });
  });

  it("treats a whitespace-only username as missing", () => {
    expect(validateLoginForm({ username: "   ", password: "secret" })).toEqual({
      username: "auth.errors.usernameRequired",
    });
  });

  it("flags a missing password with an i18n key", () => {
    expect(validateLoginForm({ username: "alice", password: "" })).toEqual({
      password: "auth.errors.passwordRequired",
    });
  });

  it("flags both fields when the form is empty", () => {
    expect(validateLoginForm({ username: "", password: "" })).toEqual({
      username: "auth.errors.usernameRequired",
      password: "auth.errors.passwordRequired",
    });
  });
});

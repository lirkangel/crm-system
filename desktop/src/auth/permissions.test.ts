import { describe, expect, it } from "vitest";

import { hasPermission } from "./permissions";
import type { CurrentUser } from "./types";

const adminUser: CurrentUser = {
  id: "u1",
  username: "admin",
  roles: ["admin"],
  permissions: ["core.users.read", "core.users.write", "core.roles.read"],
};

const viewerUser: CurrentUser = {
  id: "u2",
  username: "viewer",
  roles: ["viewer"],
  permissions: [],
};

describe("hasPermission", () => {
  it("returns false for a null user regardless of permission", () => {
    expect(hasPermission(null, "core.users.read")).toBe(false);
  });

  it("returns true when the user holds the permission", () => {
    expect(hasPermission(adminUser, "core.users.read")).toBe(true);
  });

  it("returns false when the user lacks the permission", () => {
    expect(hasPermission(viewerUser, "core.users.read")).toBe(false);
  });

  it("returns false when the user has other permissions but not the specified one", () => {
    expect(hasPermission(adminUser, "core.audit.read")).toBe(false);
  });
});

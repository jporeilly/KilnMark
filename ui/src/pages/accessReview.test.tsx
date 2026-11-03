import { describe, expect, test } from "vitest";
import type { AccessReviewEntry } from "../api";
import { access, lastSeen } from "./AccessReviewPage";
import { expiryChoices } from "./TokensPage";
import { parse } from "../router";

const entry = (e: Partial<AccessReviewEntry>): AccessReviewEntry => ({
  kind: "token",
  name: "ci",
  role: "steward",
  unmask: false,
  status: "active",
  createdBy: "token 1 (admin)",
  createdAt: "2026-11-01T09:00:00Z",
  expiresAt: null,
  lastUsedAt: null,
  signIns: null,
  ...e,
});

describe("the access review", () => {
  test("says what each entry can do and when it last did anything", () => {
    expect(access(entry({ role: "designer", unmask: true }))).toBe("designer, sees unmasked values");
    expect(access(entry({ role: null }))).toBe("no role");
    expect(lastSeen(entry({}))).toBe("Never");
    expect(lastSeen(entry({ kind: "provider", lastUsedAt: "2026-11-02T10:00:00Z", signIns: 4 }))).toMatch(/\(4 sign-ins\)$/);
  });

  test("lives under Settings", () => {
    expect(parse("#/settings/access-review")).toEqual({ page: "access-review" });
  });
});

describe("token expiry choices", () => {
  test("offer never only when the server sets no longest lifetime", () => {
    expect(expiryChoices(null).map((x) => x.value)).toEqual(["30", "90", "365", "never"]);
    expect(expiryChoices(90).map((x) => x.value)).toEqual(["30", "90"]);
    expect(expiryChoices(60).map((x) => x.value)).toEqual(["30", "60"]);
    expect(expiryChoices(7).map((x) => x.value)).toEqual(["7"]);
  });
});

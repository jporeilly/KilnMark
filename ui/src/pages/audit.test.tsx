import { describe as group, expect, test } from "vitest";
import type { AuditEvent } from "../api";
import { ACTIONS, describe } from "./AuditLogPage";
import { parse } from "../router";

const event = (action: string, detail: Record<string, unknown>): AuditEvent => ({
  id: 1,
  at: "2026-11-01T19:00:00Z",
  actor: "jo",
  tokenId: 3,
  action,
  target: "x",
  detail,
});

group("the audit log page", () => {
  test("says what each event did in a few words", () => {
    expect(describe(event("source.allow_sql", { on: true, via: "import" }))).toBe("Turned on (import)");
    expect(describe(event("source.allow_writing", { on: false, via: "form" }))).toBe("Turned off (form)");
    expect(describe(event("token.created", { name: "ci", role: "designer", unmask: true, expiresAt: null }))).toBe(
      "ci: designer, sees unmasked values, never expires",
    );
    expect(describe(event("api.refused", { method: "GET", status: 403 }))).toBe("GET 403");
    expect(describe(event("api.refused", { method: "POST", status: 401, client: "10.0.0.9", notKeptBefore: 12 }))).toBe(
      "POST 401, from 10.0.0.9, 12 more not kept before this",
    );
    expect(describe(event("mark.cleared", { field: "email" }))).toBe("field: email");
    expect(describe(event("auth.sign_in", { role: "designer", unmask: true }))).toBe(
      "designer, sees unmasked values",
    );
    expect(describe(event("auth.sign_in_refused", { reason: "No second factor." }))).toBe("No second factor.");
    expect(describe(event("flow.sql", { source: "warehouse", statements: 2, version: 3 }))).toBe(
      "2 statements on warehouse, version 3",
    );
  });

  test("offers every action the server records", () => {
    expect(ACTIONS.map((a) => a.value).sort()).toEqual(
      [
        "api.refused",
        "api.request",
        "auth.sign_in",
        "auth.sign_in_refused",
        "auth.sign_out",
        "access.review",
        "flow.sql",
        "mark.cleared",
        "mark.set",
        "source.allow_sql",
        "source.allow_writing",
        "source.deleted",
        "token.created",
        "token.revoked",
      ].sort(),
    );
  });

  test("lives under Settings", () => {
    expect(parse("#/settings/audit")).toEqual({ page: "audit" });
  });
});

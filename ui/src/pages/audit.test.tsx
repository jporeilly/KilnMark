import { describe as group, expect, test } from "vitest";
import { formatTime, type AuditEvent } from "../api";
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
    expect(describe(event("source.max_runs", { before: null, after: 2, via: "form" }))).toBe("From no limit to 2 (form)");
    expect(describe(event("source.max_runs", { before: 4, after: null, via: "import" }))).toBe(
      "From 4 to no limit (import)",
    );
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
    expect(describe(event("database.restored", { backup: "kilnmark-20261103T200000Z", takenAt: "2026-11-03T20:00:00Z" }))).toBe(
      `From kilnmark-20261103T200000Z, taken ${formatTime("2026-11-03T20:00:00Z")}`,
    );
    expect(describe(event("schedule.set", { pipeline: "nightly", cron: "0 2 * * *", zone: "Europe/London", runAs: "steward", enabled: true }))).toBe(
      "Pipeline nightly: 0 2 * * * (Europe/London), as steward",
    );
    expect(describe(event("schedule.paused", { pipeline: "nightly", cron: "@daily", reason: "the token that set it was revoked" }))).toBe(
      "Pipeline nightly: paused, the token that set it was revoked",
    );
    expect(describe(event("schedule.deleted", { flow: "close", cron: "@daily", zone: "UTC", runAs: "designer", enabled: false }))).toBe(
      "Flow close: @daily (UTC), as designer, paused",
    );
    expect(describe(event("flow.sql", { source: "warehouse", statements: 2, version: 3 }))).toBe(
      "2 statements on warehouse, version 3",
    );
    expect(describe(event("starter.added", { refused: "production" }))).toBe("Refused, nothing changed: production");
    expect(describe(event("starter.people", { action: "adopt", people: ["vera.lane@kilnmark.com", "sam.okafor@kilnmark.com"] }))).toBe(
      "Brought into the pack: vera.lane@kilnmark.com, sam.okafor@kilnmark.com",
    );
  });

  test("says who the people command added, and what an import did, never with a password", () => {
    expect(
      describe(event("auth.person_added", { person: "rita@bank.example", groups: ["kilnmark-admins"], via: "add" })),
    ).toBe("Added rita@bank.example (kilnmark-admins) one at a time, with a password they change at their first sign-in");
    expect(
      describe(
        event("auth.person_added", {
          person: "priya.shah@example.com",
          groups: ["kilnmark-designers", "kilnmark-sensitive-data"],
          via: "import",
        }),
      ),
    ).toBe(
      "Added priya.shah@example.com (kilnmark-designers, kilnmark-sensitive-data) from a file, with a password they change at their first sign-in",
    );
    expect(describe(event("auth.people_imported", { made: 4, alreadyThere: 1, refused: 0 }))).toBe(
      "Made 4, already there 1, refused 0",
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
        "auth.person_added",
        "auth.people_imported",
        "access.review",
        "unmask.requested",
        "unmask.approved",
        "unmask.refused",
        "database.restored",
        "schedule.set",
        "schedule.deleted",
        "schedule.paused",
        "starter.added",
        "starter.removed",
        "starter.people",
        "flow.sql",
        "mark.cleared",
        "mark.set",
        "source.allow_sql",
        "source.allow_writing",
        "source.max_runs",
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

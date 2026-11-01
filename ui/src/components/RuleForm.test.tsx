// @vitest-environment jsdom
import { act } from "react";
import { createRoot } from "react-dom/client";
import { afterEach, describe, expect, test, vi } from "vitest";
import type { Asset, QualityRule, Source } from "../api";

/** Two sources: lab-sql has Allow SQL on, lab-db does not. */
const ALLOW_SQL: Record<string, boolean> = { "lab-sql": true, "lab-db": false };

const asset = (urn: string): Asset => ({
  urn,
  source: urn.split(":")[2],
  kind: "table",
  container: "crm",
  name: "customers",
  description: null,
  primaryKey: [],
  rowCount: 10,
  rowCountComplete: true,
  firstSeenAt: "2026-11-01T09:00:00Z",
  lastScannedAt: "2026-11-01T09:00:00Z",
  removedAt: null,
  fields: [],
});

const sourceCalls: string[] = [];

vi.mock("../api", async (original) => {
  const real = await original<typeof import("../api")>();
  return {
    ...real,
    api: {
      ...real.api,
      asset: vi.fn(async (urn: string) => asset(urn)),
      source: vi.fn(async (name: string) => {
        sourceCalls.push(name);
        return { name, kind: "postgresql", settings: ALLOW_SQL[name] ? { allow_sql: "true" } : {} } as unknown as Source;
      }),
    },
  };
});

const { RuleForm } = await import("./RuleForm");

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
const host = document.createElement("div");
document.body.appendChild(host);
const root = createRoot(host);

afterEach(() => {
  act(() => root.render(<></>));
  sourceCalls.length = 0;
});

const settle = () =>
  act(async () => {
    await new Promise((r) => setTimeout(r, 0));
  });

const checks = () =>
  Array.from(host.querySelectorAll<HTMLOptionElement>("#rule-type option")).map((o) => o.value);

const render = async (props: { urn: string; customSql: boolean; rule?: QualityRule }) => {
  await act(async () => root.render(<RuleForm {...props} onSaved={() => {}} />));
  await settle();
  await settle();
};

const customRule = (urn: string): QualityRule => ({
  id: 7,
  assetUrn: urn,
  field: null,
  name: "No negative amounts",
  type: "custom_sql",
  dimension: "validity",
  params: { sql: "SELECT * FROM ${table} WHERE amount < 0" },
  thresholdUnit: "count",
  warnAbove: null,
  failAbove: 0,
  actions: { webhook: null, task: false },
  enabled: true,
  origin: "manual",
  createdBy: "designer",
  createdAt: "2026-11-01T09:00:00Z",
  updatedAt: "2026-11-01T09:00:00Z",
});

describe("custom SQL in the rule form", () => {
  test("is offered to a caller with the grant where the source has Allow SQL on", async () => {
    await render({ urn: "urn:kilnmark:lab-sql:crm.customers", customSql: true });
    expect(checks()).toContain("custom_sql");
    expect(sourceCalls).toEqual(["lab-sql"]);
  });

  test("is not offered where the source has Allow SQL off, even with the grant", async () => {
    await render({ urn: "urn:kilnmark:lab-db:crm.customers", customSql: true });
    expect(checks()).not.toContain("custom_sql");
    expect(checks()).toContain("not_null");
  });

  test("is not offered without the grant, and the source is not asked", async () => {
    await render({ urn: "urn:kilnmark:lab-sql:crm.customers", customSql: false });
    expect(checks()).not.toContain("custom_sql");
    expect(sourceCalls).toEqual([]);
  });

  test("a custom rule already written stays editable where Allow SQL is off, and says it cannot run", async () => {
    const urn = "urn:kilnmark:lab-db:crm.customers";
    await render({ urn, customSql: true, rule: customRule(urn) });
    expect(checks()).toContain("custom_sql");
    expect(host.querySelector<HTMLSelectElement>("#rule-type")?.value).toBe("custom_sql");
    expect(host.querySelector<HTMLTextAreaElement>("#rule-sql")?.value).toContain("amount < 0");
    expect(host.querySelector("#rule-sql-off")?.textContent).toContain("does not have Allow SQL on");
  });

  test("a custom rule where Allow SQL is on shows no warning", async () => {
    const urn = "urn:kilnmark:lab-sql:crm.customers";
    await render({ urn, customSql: true, rule: customRule(urn) });
    expect(host.querySelector("#rule-sql-off")).toBeNull();
  });
});

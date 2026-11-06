// @vitest-environment jsdom
import { act } from "react";
import { createRoot } from "react-dom/client";
import { afterEach, describe, expect, test, vi } from "vitest";
import type { GeneratedSql, SqlRequest, TableDetail } from "../api";

const detail: TableDetail = {
  path: "sales.orders",
  schema: "sales",
  table: "orders",
  kind: "table",
  columns: [
    { name: "order_id", position: 1, sourceType: "int8", size: null, scale: null, nullable: false, hasDefault: false, generated: true, kind: "int64" },
    { name: "total", position: 2, sourceType: "numeric", size: 12, scale: 2, nullable: false, hasDefault: false, generated: false, kind: "decimal" },
  ],
  primaryKey: ["order_id"],
  foreignKeys: [],
  referencedBy: [],
  indexes: [
    { name: "orders_pkey", columns: ["order_id"], unique: true, primaryKey: true, method: null, partial: false },
    { name: "orders_total", columns: ["total"], unique: false, primaryKey: false, method: null, partial: false },
  ],
  estimatedRows: 10,
};

const answer: GeneratedSql = {
  dialect: "postgresql",
  sql: ['CREATE TABLE "sales"."orders" (\n  "order_id" bigint NOT NULL\n)'],
  leftOut: ["Checks, comments, triggers, grants and partitions are never copied."],
};
const generateSql = vi.fn(async (_name: string, _r: SqlRequest) => answer);

vi.mock("../api", async (original) => {
  const real = await original<typeof import("../api")>();
  return { ...real, api: { ...real.api, generateSql } };
});

const { GenerateSql, script, sqlFileName } = await import("./GenerateSql");

const host = document.createElement("div");
document.body.appendChild(host);
const root = createRoot(host);

afterEach(() => {
  act(() => root.render(<></>));
  vi.clearAllMocks();
});

const settle = () =>
  act(async () => {
    await new Promise((r) => setTimeout(r, 0));
  });
const button = (text: string) =>
  [...host.querySelectorAll("button")].find((b) => b.textContent?.trim().startsWith(text)) as HTMLButtonElement;
const select = (label: string) =>
  [...host.querySelectorAll("label")].find((l) => l.textContent === label)!.control as HTMLSelectElement;

describe("the SQL generator", () => {
  test("ends each statement and names the file after the table", () => {
    expect(script({ dialect: "postgresql", sql: ["DROP TABLE a", "DROP TABLE b"], leftOut: [] })).toBe(
      "DROP TABLE a;\n\nDROP TABLE b;\n",
    );
    expect(sqlFileName("sales.orders", "create-table")).toBe("sales.orders-create-table.sql");
    expect(sqlFileName("a b/c", "truncate")).toBe("a_b_c-truncate.sql");
  });

  test("asks for the source's own dialect, shows the text and what was left out", async () => {
    await act(async () => root.render(<GenerateSql source="db" sourceKind="postgresql" detail={detail} />));
    await act(async () => button("Generate SQL").click());
    await settle();
    expect(generateSql).toHaveBeenCalledWith("db", {
      statement: "create-table",
      asset: "sales.orders",
      dialect: "postgresql",
      columns: null,
      index: null,
    });
    expect(host.querySelector("pre")?.textContent).toContain('CREATE TABLE "sales"."orders"');
    expect(host.textContent).toContain("never copied");

    // changing the choice clears the answer, so Save cannot pair it with another statement
    const statement = select("Statement");
    await act(async () => {
      statement.value = "truncate";
      statement.dispatchEvent(new Event("change", { bubbles: true }));
    });
    expect(host.querySelector("pre")).toBeNull();
  });

  test("offers only indexes that are not the key's for DROP INDEX", async () => {
    await act(async () => root.render(<GenerateSql source="db" sourceKind="postgresql" detail={detail} />));
    const statement = select("Statement");
    await act(async () => {
      statement.value = "drop-index";
      statement.dispatchEvent(new Event("change", { bubbles: true }));
    });
    const options = [...select("Index").options].map((o) => o.value);
    expect(options).toEqual(["", "orders_total"]);
  });

  test("needs a database chosen for a source without a dialect of its own", async () => {
    await act(async () => root.render(<GenerateSql source="db" sourceKind="jdbc" detail={detail} />));
    expect(button("Generate SQL").disabled).toBe(true);
  });
});

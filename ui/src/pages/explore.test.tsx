// @vitest-environment jsdom
import { act } from "react";
import { createRoot } from "react-dom/client";
import { afterEach, describe, expect, test, vi } from "vitest";
import type { AssetList, ContainerList, Preview, TableDetail } from "../api";
import { href, parse } from "../router";

const schemas: ContainerList = {
  setting: "schemas",
  label: "Schemas",
  counted: "tables and views",
  containers: [
    { name: "shop", count: 2, selected: false },
    { name: "crm", count: 1, selected: false },
  ],
  selected: [],
  missing: [],
  total: 3,
  nothingToScan: false,
  warnings: [],
};

const tables: AssetList = {
  container: "shop",
  total: 2,
  offset: 0,
  items: [
    { path: "shop.orders", name: "orders", kind: "table", readable: true },
    { path: "shop.big_orders", name: "big_orders", kind: "view", readable: true },
  ],
};

const orders: TableDetail = {
  path: "shop.orders",
  kind: "table",
  columns: [
    { name: "order_id", position: 1, sourceType: "int4", size: null, scale: null, nullable: false, hasDefault: false, generated: true, kind: "int32" },
    { name: "total", position: 2, sourceType: "numeric", size: 12, scale: 2, nullable: true, hasDefault: true, generated: false, kind: "decimal" },
    { name: "customer", position: 3, sourceType: "int4", size: null, scale: null, nullable: false, hasDefault: false, generated: false, kind: "int32" },
  ],
  primaryKey: ["order_id"],
  foreignKeys: [{ name: "orders_customer", table: "shop.orders", columns: ["customer"], toTable: "crm.customers", toColumns: ["id"] }],
  referencedBy: [],
  indexes: [
    { name: "orders_pkey", columns: ["order_id"], unique: true, primaryKey: true, method: null, partial: false },
    { name: "orders_lower", columns: ["(expression)"], unique: false, primaryKey: false, method: null, partial: false },
    { name: "orders_open", columns: ["customer"], unique: true, primaryKey: false, method: null, partial: true },
  ],
  estimatedRows: 1204000,
};

const preview: Preview = {
  asset: "shop.orders",
  columns: [{ name: "order_id", kind: "int32", sourceType: "int4" }],
  rows: [["1"]],
  rowCount: 1,
  truncated: false,
  details: {},
} as Preview;

const containers = vi.fn(async () => schemas);
const assets = vi.fn(async () => tables);
const explore = vi.fn(async () => orders);
const countRows = vi.fn(async () => ({ asset: "shop.orders", rows: 1204311 }));
const previewFn = vi.fn(async () => preview);

vi.mock("../api", async (original) => {
  const real = await original<typeof import("../api")>();
  return { ...real, api: { ...real.api, containers, assets, explore, countRows, preview: previewFn } };
});

const { SourceExplore, estimateText } = await import("./SourceExplore");

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

describe("Explore", () => {
  test("keeps the schema and table in the address", () => {
    expect(href.source("db", "shop", "orders")).toBe("#/source/db?schema=shop&table=orders");
    expect(href.source("db", null, "orders")).toBe("#/source/db");
    expect(parse("#/source/db?schema=shop&table=orders")).toEqual({
      page: "source",
      name: "db",
      schema: "shop",
      table: "orders",
    });
    expect(parse("#/source/db")).toEqual({ page: "source", name: "db", schema: null, table: null });
  });

  test("says a row count is the database's estimate, and when there is none", () => {
    expect(estimateText(orders)).toBe("About 1,204,000 rows (the database's estimate).");
    expect(estimateText({ ...orders, estimatedRows: null })).toBe("The database keeps no estimate of its rows.");
    expect(estimateText({ ...orders, kind: "view" })).toBe("A view: its rows are counted when read.");
  });

  test("lists schemas, and asks for a schema before tables", async () => {
    await act(async () => root.render(<SourceExplore source="db" schema={null} table={null} />));
    await settle();
    expect(host.textContent).toContain("shop");
    expect(host.textContent).toContain("Choose a schema");
    expect(assets).not.toHaveBeenCalled();
  });

  test("shows a table's columns, keys and indexes, and counts rows only when asked", async () => {
    await act(async () => root.render(<SourceExplore source="db" schema="shop" table="orders" />));
    await settle();
    expect(assets).toHaveBeenCalledWith("db", "shop", 0);
    expect(explore).toHaveBeenCalledWith("db", "shop.orders");
    const text = host.textContent ?? "";
    expect(text).toContain("numeric(12,2)");
    expect(text).toContain("A default when left out");
    expect(text).toContain("The database");
    expect(text).toContain("About 1,204,000 rows");
    expect(countRows).not.toHaveBeenCalled();

    await act(async () => button("Count exactly").click());
    await settle();
    expect(countRows).toHaveBeenCalledWith("db", "shop.orders");
    expect(host.textContent).toContain("1,204,311 rows, counted just now.");

    await act(async () => button("Keys").click());
    expect(host.textContent).toContain("crm.customers (id)");
    await act(async () => button("Indexes").click());
    expect(host.textContent).toContain("(expression)");
    expect(host.textContent).toContain("Yes, among the rows it covers");
    expect(previewFn).not.toHaveBeenCalled();
    await act(async () => button("Preview").click());
    await settle();
    expect(previewFn).toHaveBeenCalledWith("db", "shop.orders", 50);
  });
});

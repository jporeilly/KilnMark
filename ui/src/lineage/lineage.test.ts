import { describe, expect, test } from "vitest";
import { assetName, BOX_WIDTH, layoutLineage, lineageModel, type LineageLink } from "./lineage";
import { assetLinks } from "./AssetLineagePanel";

const DB = "urn:kilnmark:starter-samples-db:public.shop_orders";
const CUSTOMERS = "urn:kilnmark:starter-samples-db:public.shop_customers";
const NAMED = "urn:kilnmark:starter-samples-files:samples/combine/orders_named.csv";
const TOTALS = "urn:kilnmark:starter-samples-files:samples/combine/totals.parquet";

describe("readable names from asset URNs", () => {
  test("a table keeps its schema and table", () => {
    expect(assetName(DB)).toEqual({
      urn: DB,
      source: "starter-samples-db",
      kind: "table",
      name: "public.shop_orders",
      folder: null,
      format: null,
    });
  });

  test("a file shows its own name, with its folder apart", () => {
    const a = assetName(NAMED);
    expect([a.kind, a.name, a.folder, a.source, a.format]).toEqual([
      "file",
      "orders_named.csv",
      "samples/combine",
      "starter-samples-files",
      "csv",
    ]);
  });

  test("a file at the root is a file, not a table", () => {
    const a = assetName("urn:kilnmark:share:orders.csv");
    expect([a.kind, a.name, a.folder]).toEqual(["file", "orders.csv", null]);
    // A table whose name has a dot but no file's extension stays a table.
    expect(assetName("urn:kilnmark:pg:sales.orders").kind).toBe("table");
  });

  test("colons in the path belong to the path, not the source", () => {
    const file = assetName("urn:kilnmark:lake:s3:raw/2026-11-09T10:00/a.csv");
    expect([file.source, file.name, file.folder]).toEqual(["lake", "a.csv", "s3:raw/2026-11-09T10:00"]);
    const table = assetName("urn:kilnmark:mssql:sales:dbo.orders");
    expect([table.source, table.kind, table.name]).toEqual(["mssql", "table", "sales:dbo.orders"]);
  });

  test("the catalogue's escapes in a file's path are undone for showing, the URN is kept", () => {
    const urn = "urn:kilnmark:share:out/a%23b%2523c.csv";
    const a = assetName(urn);
    expect(a.name).toBe("a#b%23c.csv");
    expect(a.urn).toBe(urn);
  });

  test("a URN that does not read as one is shown whole", () => {
    for (const urn of ["orders", "urn:kilnmark:nopath", "urn:kilnmark:src:"]) {
      expect(assetName(urn)).toMatchObject({ kind: "asset", name: urn, source: "" });
    }
  });
});

describe("the lineage graph", () => {
  // Two inputs; totals came only from orders, orders_named from both.
  const links: LineageLink[] = [
    { node: "write_named", input: DB, output: NAMED },
    { node: "write_named", input: CUSTOMERS, output: NAMED },
    { node: "write_totals", input: DB, output: TOTALS },
  ];

  test("draws only the pairs recorded, each once", () => {
    const model = lineageModel([...links, links[0]]);
    expect(model.edges.map((e) => [e.from, e.to])).toEqual([
      [DB, NAMED],
      [CUSTOMERS, NAMED],
      [DB, TOTALS],
    ]);
    expect(model.edges.some((e) => e.from === CUSTOMERS && e.to === TOTALS)).toBe(false);
    expect(model.assets.map((a) => a.urn)).toEqual([DB, NAMED, CUSTOMERS, TOTALS]);
  });

  test("an asset that came from itself is not a line", () => {
    const model = lineageModel([{ input: DB, output: DB }, ...links]);
    expect(model.fromItself).toEqual([DB]);
    expect(model.edges.some((e) => e.from === e.to)).toBe(false);
  });

  test("puts inputs left of outputs", () => {
    const placed = layoutLineage(lineageModel(links));
    for (const input of [DB, CUSTOMERS])
      for (const output of [NAMED, TOTALS]) {
        expect(placed.at.get(input)!.x + BOX_WIDTH).toBeLessThan(placed.at.get(output)!.x);
      }
    expect(placed.band).not.toBeNull();
    expect(placed.band!.x).toBeGreaterThan(placed.at.get(DB)!.x + BOX_WIDTH);
    expect(placed.band!.x + placed.band!.width).toBeLessThan(placed.at.get(NAMED)!.x);
  });

  test("a chain keeps each asset left of what came from it", () => {
    const placed = layoutLineage(lineageModel([...links, { input: NAMED, output: "urn:kilnmark:pg:mart.report" }]));
    expect(placed.at.get(NAMED)!.x).toBeLessThan(placed.at.get("urn:kilnmark:pg:mart.report")!.x);
    expect(placed.at.get(DB)!.x).toBeLessThan(placed.at.get(NAMED)!.x);
  });

  test("everything fits the drawing and no two boxes overlap", () => {
    const placed = layoutLineage(lineageModel(links), { labels: true });
    const boxes = [...placed.at.entries()];
    for (const [, p] of boxes) {
      expect(p.x).toBeGreaterThanOrEqual(0);
      expect(p.x + BOX_WIDTH).toBeLessThanOrEqual(placed.width);
    }
    for (const [a, p] of boxes)
      for (const [b, q] of boxes) {
        if (a >= b) continue;
        const apart = p.x + BOX_WIDTH <= q.x || q.x + BOX_WIDTH <= p.x || Math.abs(p.y - q.y) >= 66;
        expect(apart, `${a} and ${b}`).toBe(true);
      }
  });

  test("on an asset's page each line is labelled with its pipelines", () => {
    const model = lineageModel([
      { input: DB, output: NAMED, pipeline: "orders-combine" },
      { input: DB, output: NAMED, pipeline: "orders-nightly" },
    ]);
    expect(model.edges).toHaveLength(1);
    expect(model.edges[0].pipelines).toEqual(["orders-combine", "orders-nightly"]);
    const placed = layoutLineage(model, { labels: true });
    expect(placed.edges[0].label).not.toBeNull();
  });
});

describe("an asset's lineage from the API", () => {
  test("becomes pairs into and out of the asset, once per pipeline", () => {
    const entry = (urn: string, pipeline: string, runId: number) => ({ urn, pipeline, runId, node: "w", recordedAt: "2026-11-09T10:00:00Z" });
    const pairs = assetLinks({
      urn: NAMED,
      upstream: [entry(DB, "orders-combine", 2), entry(DB, "orders-combine", 1), entry(CUSTOMERS, "orders-combine", 1)],
      downstream: [entry(TOTALS, "totals", 3)],
    });
    expect(pairs).toEqual([
      { input: DB, output: NAMED, pipeline: "orders-combine" },
      { input: CUSTOMERS, output: NAMED, pipeline: "orders-combine" },
      { input: NAMED, output: TOTALS, pipeline: "totals" },
    ]);
  });
});

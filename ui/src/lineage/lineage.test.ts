import { describe, expect, test } from "vitest";
import {
  assetName,
  BOX_HEIGHT,
  BOX_WIDTH,
  edgeLabel,
  labelWidth,
  layoutLineage,
  lineageModel,
  type Cubic,
  type LineageLayout,
  type LineageLink,
  type Point,
} from "./lineage";
import { assetLinks, boxKind } from "./AssetLineagePanel";

const DB = "urn:kilnmark:starter-samples-db:public.shop_orders";
const CUSTOMERS = "urn:kilnmark:starter-samples-db:public.shop_customers";
const NAMED = "urn:kilnmark:starter-samples-files:samples/combine/orders_named.csv";
const TOTALS = "urn:kilnmark:starter-samples-files:samples/combine/totals.parquet";
const t = (name: string) => `urn:kilnmark:pg:public.${name}`;

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

  test("the catalogue's kind wins over the guess", () => {
    expect(assetName("urn:kilnmark:pg:public.json", "table")).toMatchObject({ kind: "table", name: "public.json" });
    expect(assetName("urn:kilnmark:share:README", "file")).toMatchObject({ kind: "file", name: "README", folder: null });
    expect(boxKind("view")).toBe("table");
    expect(boxKind("partitioned table")).toBe("table");
    expect(boxKind("sheet")).toBe("file");
    expect(boxKind("document")).toBeUndefined();
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

/** Points along a line, ends left out (they touch their own boxes). */
function along(curves: Cubic[]): Point[] {
  const out: Point[] = [];
  for (const [a, b, c, d] of curves)
    for (let i = 1; i < 40; i++) {
      const s = i / 40;
      const u = 1 - s;
      out.push({
        x: u * u * u * a.x + 3 * u * u * s * b.x + 3 * u * s * s * c.x + s * s * s * d.x,
        y: u * u * u * a.y + 3 * u * u * s * b.y + 3 * u * s * s * c.y + s * s * s * d.y,
      });
    }
  return out;
}

/** Every line passes only through the boxes at its two ends, and everything lies in the drawing. */
function expectClear(placed: LineageLayout) {
  for (const e of placed.edges) {
    const points = along(e.curves);
    for (const [urn, p] of placed.at) {
      if (urn === e.from || urn === e.to) continue;
      const through = points.find((q) => q.x > p.x + 1 && q.x < p.x + BOX_WIDTH - 1 && q.y > p.y + 1 && q.y < p.y + BOX_HEIGHT - 1);
      expect(through, `the line ${e.from} to ${e.to} passes behind ${urn}`).toBeUndefined();
    }
    for (const q of [...points, ...e.curves.flat()]) {
      expect(q.x).toBeGreaterThanOrEqual(0);
      expect(q.x).toBeLessThanOrEqual(placed.width);
      expect(q.y).toBeGreaterThanOrEqual(0);
      expect(q.y).toBeLessThanOrEqual(placed.height);
    }
    if (e.label) {
      const w = labelWidth(edgeLabel(e)) / 2;
      expect(e.label.x - w).toBeGreaterThanOrEqual(0);
      expect(e.label.x + w).toBeLessThanOrEqual(placed.width);
    }
    // Nor behind another line's label, whose background would hide it.
    for (const o of placed.edges) {
      if (o === e || !o.label) continue;
      const w = labelWidth(edgeLabel(o)) / 2;
      const behind = points.find((q) => Math.abs(q.x - o.label!.x) < w - 1 && Math.abs(q.y - o.label!.y) < 11 - 1);
      expect(behind, `the line ${e.from} to ${e.to} passes behind the label of ${o.from} to ${o.to}`).toBeUndefined();
    }
  }
  for (const p of placed.at.values()) {
    expect(p.x).toBeGreaterThanOrEqual(0);
    expect(p.y).toBeGreaterThanOrEqual(0);
    expect(p.x + BOX_WIDTH).toBeLessThanOrEqual(placed.width);
    expect(p.y + BOX_HEIGHT).toBeLessThanOrEqual(placed.height);
  }
}

/** A label sits on its own line: the line runs level through it. */
function expectOnLine(e: LineageLayout["edges"][number]) {
  const nearest = Math.min(...along(e.curves).map((p) => Math.hypot(p.x - e.label!.x, p.y - e.label!.y)));
  expect(nearest).toBeLessThan(2);
}

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
    expect(model.assets.map((a) => a.urn)).toEqual([DB, NAMED, CUSTOMERS, TOTALS]);
    const placed = layoutLineage(model);
    expect(placed.edges.map((e) => [e.from, e.to])).toEqual(model.edges.map((e) => [e.from, e.to]));
  });

  test("an asset that came from itself is not a line", () => {
    const model = lineageModel([{ input: DB, output: DB }, ...links]);
    expect(model.fromItself).toEqual([DB]);
    expect(model.edges.some((e) => e.from === e.to)).toBe(false);
  });

  test("puts inputs left of outputs, with the band between", () => {
    const placed = layoutLineage(lineageModel(links));
    for (const input of [DB, CUSTOMERS])
      for (const output of [NAMED, TOTALS]) {
        expect(placed.at.get(input)!.x + BOX_WIDTH).toBeLessThan(placed.at.get(output)!.x);
      }
    expect(placed.band).not.toBeNull();
    expect(placed.band!.x).toBeGreaterThan(placed.at.get(DB)!.x + BOX_WIDTH);
    expect(placed.band!.x + placed.band!.width).toBeLessThan(placed.at.get(NAMED)!.x);
    expectClear(placed);
  });

  test("every input is in the first column and every output in the last, whatever the chain", () => {
    // y feeds only b, two columns on; c came only from x. Neither may drift into the middle.
    const [x, y, a, b, c] = ["x", "y", "a", "b", "c"].map(t);
    const placed = layoutLineage(
      lineageModel([
        { input: x, output: a },
        { input: a, output: b },
        { input: y, output: b },
        { input: x, output: c },
      ]),
    );
    const col = (u: string) => placed.at.get(u)!.x;
    expect(col(y)).toBe(col(x));
    expect(col(c)).toBe(col(b));
    expect(col(x)).toBeLessThan(col(a));
    expect(col(a)).toBeLessThan(col(b));
    expect(placed.columns).toEqual({ first: col(x), last: col(b) });
    expectClear(placed);
  });

  test("an asset that only came from itself goes with the outputs, not before the inputs", () => {
    const [t0, t1, t2, t3, t4, t5, t7, t8, t9] = ["t0", "t1", "t2", "t3", "t4", "t5", "t7", "t8", "t9"].map(t);
    const pairs: [string, string][] = [
      [t2, t2],
      [t4, t5],
      [t1, t5],
      [t1, t9],
      [t0, t7],
      [t1, t8],
      [t3, t4],
    ];
    const placed = layoutLineage(lineageModel(pairs.map(([input, output]) => ({ input, output }))));
    const col = (u: string) => placed.at.get(u)!.x;
    for (const input of [t0, t1, t3]) expect(col(input)).toBe(placed.columns!.first);
    for (const output of [t2, t5, t7, t8, t9]) expect(col(output)).toBe(placed.columns!.last);
    expectClear(placed);
  });

  test("a line that skips a column passes between its boxes, never behind one", () => {
    const [t1, t2, t3, t4, t5, t6, t7] = ["t1", "t2", "t3", "t4", "t5", "t6", "t7"].map(t);
    const pairs: [string, string][] = [
      [t1, t7],
      [t1, t6],
      [t1, t2],
      [t2, t3],
      [t3, t6],
      [t2, t4],
      [t4, t5],
    ];
    const model = lineageModel(pairs.map(([input, output]) => ({ input, output, pipeline: "p" })));
    expectClear(layoutLineage(model));
    expectClear(layoutLineage(model, { labels: true }));
  });

  test("a loop of lineage goes back apart from the line forward, inside the drawing, with its label on it", () => {
    const [x, c] = [t("x"), t("c")];
    for (const labels of [false, true]) {
      const placed = layoutLineage(
        lineageModel([
          { input: x, output: c, pipeline: "p1" },
          { input: c, output: x, pipeline: "p2" },
        ]),
        { labels },
      );
      expectClear(placed);
      const back = placed.edges.find((e) => e.back)!;
      const forward = placed.edges.find((e) => !e.back)!;
      expect(back).toBeDefined();
      // It leaves the left side of the right box and ends at the right side of the left one.
      const [from, to] = [placed.at.get(back.from)!, placed.at.get(back.to)!];
      expect(back.curves[0][0].x).toBe(from.x);
      expect(back.curves[back.curves.length - 1][3].x).toBe(to.x + BOX_WIDTH + 2);
      // It does not lie on the line forward: halfway between the boxes the two are well apart.
      const half = (cs: Cubic[]) => {
        const midX = (from.x + to.x + BOX_WIDTH) / 2;
        return along(cs).reduce((best, p) => (Math.abs(p.x - midX) < Math.abs(best.x - midX) ? p : best));
      };
      expect(Math.abs(half(back.curves).y - half(forward.curves).y)).toBeGreaterThan(20);
      // Nor do they meet at the boxes' sides.
      expect(back.curves[0][0].y).not.toBe(forward.curves[forward.curves.length - 1][3].y);
      if (labels) expectOnLine(back);
    }
  });

  test("on an asset's page each line is labelled with its pipelines, on the line", () => {
    const model = lineageModel([
      { input: DB, output: NAMED, pipeline: "orders-combine" },
      { input: DB, output: NAMED, pipeline: "orders-nightly" },
    ]);
    expect(model.edges).toHaveLength(1);
    expect(model.edges[0].pipelines).toEqual(["orders-combine", "orders-nightly"]);
    const placed = layoutLineage(model, { labels: true });
    const e = placed.edges[0];
    expect(e.label).not.toBeNull();
    expectOnLine(e);
    expectClear(placed);
  });

  test("an asset's lines fan out without passing behind each other's labels", () => {
    const c = t("c");
    const fan = ["orders-nightly", "orders-combine", "totals", "report-weekly", "archive"];
    const model = lineageModel([
      ...fan.map((p, i) => ({ input: c, output: t(`d${i}`), pipeline: p })),
      ...fan.map((p, i) => ({ input: t(`u${i}`), output: c, pipeline: p })),
    ]);
    const placed = layoutLineage(model, { labels: true });
    for (const e of placed.edges) expectOnLine(e);
    expectClear(placed);
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

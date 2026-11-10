// @vitest-environment jsdom
import { afterEach, describe, expect, test } from "vitest";
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { href } from "../router";
import { LineageGraph } from "./LineageGraph";
import type { LineageLink } from "./lineage";

let root: Root | null = null;

afterEach(async () => {
  if (root) await act(async () => root!.unmount());
  root = null;
  document.body.innerHTML = "";
});

async function show(node: React.ReactNode): Promise<HTMLDivElement> {
  const host = document.createElement("div");
  document.body.append(host);
  root = createRoot(host);
  await act(async () => root!.render(node));
  return host;
}

const DB = "urn:kilnmark:starter-samples-db:public.shop_orders";
const CUSTOMERS = "urn:kilnmark:starter-samples-db:public.shop_customers";
const NAMED = "urn:kilnmark:starter-samples-files:samples/combine/orders_named.csv";
const TOTALS = "urn:kilnmark:starter-samples-files:samples/combine/totals.parquet";
const links: LineageLink[] = [
  { node: "write_named", input: DB, output: NAMED },
  { node: "write_named", input: CUSTOMERS, output: NAMED },
  { node: "write_totals", input: DB, output: TOTALS },
];

const boxes = (host: HTMLElement) => [...host.querySelectorAll<HTMLAnchorElement>("a.lg-box")];
const box = (host: HTMLElement, urn: string) => boxes(host).find((a) => a.title === urn)!;
/** What a box's link is described by, as assistive technology reads it. */
const described = (a: HTMLElement) => document.getElementById(a.getAttribute("aria-describedby") ?? "")?.textContent ?? "";
const lines = (host: HTMLElement) => [...host.querySelectorAll("path.lg-line")].map((p) => [p.getAttribute("data-from"), p.getAttribute("data-to")]);

describe("a run's lineage graph", () => {
  test("is a figure with a name and, last in it, a caption saying what it holds", async () => {
    const host = await show(<LineageGraph links={links} pipeline="orders-combine" label="Lineage of run 7" />);
    const figure = host.querySelector("figure")!;
    expect(document.getElementById(figure.getAttribute("aria-labelledby")!)!.textContent).toBe("Lineage of run 7");
    const caption = document.getElementById(figure.getAttribute("aria-describedby")!)!;
    expect(caption.tagName).toBe("FIGCAPTION");
    expect(figure.lastElementChild).toBe(caption);
    expect(caption.textContent).toContain("2 inputs went through the pipeline orders-combine into 2 outputs.");
    expect(host.querySelector(".lg-band")!.textContent).toBe("Pipeline orders-combine");
  });

  test("shows readable names, kinds and sources, with the URN only on hover", async () => {
    const host = await show(<LineageGraph links={links} pipeline="orders-combine" label="Lineage" />);
    const file = box(host, NAMED);
    expect(file.querySelector(".lg-name")!.textContent).toBe("orders_named.csv");
    expect(file.querySelector(".lg-folder")!.textContent).toBe("samples/combine/");
    expect(file.textContent).toBe("orders_named.csvFile in samples/combine/in starter-samples-files");
    const table = box(host, DB);
    expect(table.textContent).toBe("public.shop_ordersTablein starter-samples-db");
    for (const a of boxes(host)) expect(a.textContent).not.toContain("urn:");
  });

  test("each box is a link to its catalogue page, in the drawing's order", async () => {
    const host = await show(<LineageGraph links={links} pipeline="orders-combine" label="Lineage" />);
    expect(box(host, NAMED).getAttribute("href")).toBe(href.asset(NAMED));
    expect(boxes(host).map((a) => a.getAttribute("href")).sort()).toEqual([DB, CUSTOMERS, NAMED, TOTALS].map((u) => href.asset(u)).sort());
    // Tab goes down the inputs, then down the outputs.
    const order = boxes(host).map((a) => a.title);
    expect(new Set(order.slice(0, 2))).toEqual(new Set([DB, CUSTOMERS]));
    expect(new Set(order.slice(2))).toEqual(new Set([NAMED, TOTALS]));
  });

  test("draws exactly the recorded pairs", async () => {
    const host = await show(<LineageGraph links={links} pipeline="orders-combine" label="Lineage" />);
    expect(lines(host)).toEqual(links.map((l) => [l.input, l.output]));
  });

  test("each box is described in words by what it came from and went into", async () => {
    const host = await show(<LineageGraph links={links} pipeline="orders-combine" label="Lineage" />);
    expect(described(box(host, NAMED))).toBe("Came from public.shop_orders and public.shop_customers.");
    expect(described(box(host, TOTALS))).toBe("Came from public.shop_orders.");
    expect(described(box(host, CUSTOMERS))).toBe("Went into orders_named.csv.");
    expect(described(box(host, DB))).toBe("Went into orders_named.csv and totals.parquet.");
  });

  test("tells apart two files of one name in one source by their folders", async () => {
    const a = "urn:kilnmark:share:a/x.csv";
    const b = "urn:kilnmark:share:b/x.csv";
    const host = await show(
      <LineageGraph
        links={[
          { input: a, output: NAMED },
          { input: b, output: NAMED },
        ]}
        pipeline="p"
        label="Lineage"
      />,
    );
    expect(described(box(host, NAMED))).toBe("Came from a/x.csv in share and b/x.csv in share.");
  });

  test("a run that wrote only what it read says so, and marks it written", async () => {
    const host = await show(<LineageGraph links={[{ input: DB, output: DB }]} pipeline="dedupe" label="Lineage" />);
    expect(host.querySelector("figcaption")!.textContent).toContain("The pipeline dedupe wrote 1 output. One also came from itself.");
    expect(box(host, DB).classList.contains("is-output")).toBe(true);
    expect(box(host, DB).textContent).toContain("in starter-samples-db · ↻ came from itself");
    expect(described(box(host, DB))).toBe("Came from itself.");
    expect(lines(host)).toEqual([]);
  });

  test("focusing a box picks out its lines, and pointing away keeps them while it has focus", async () => {
    const host = await show(<LineageGraph links={links} pipeline="orders-combine" label="Lineage" />);
    await act(async () => box(host, CUSTOMERS).focus());
    const near = () => [...host.querySelectorAll("path.lg-line.is-near")].map((p) => p.getAttribute("data-from"));
    expect(near()).toEqual([CUSTOMERS]);
    expect(host.querySelectorAll("path.lg-line.is-faded")).toHaveLength(2);
    await act(async () => {
      box(host, TOTALS).dispatchEvent(new MouseEvent("mouseover", { bubbles: true }));
      box(host, TOTALS).dispatchEvent(new MouseEvent("mouseout", { bubbles: true }));
    });
    expect(near()).toEqual([CUSTOMERS]);
  });

  test("keeps the list of pairs, with its links, under Show the lineage as a list", async () => {
    const host = await show(<LineageGraph links={links} pipeline="orders-combine" label="Lineage" />);
    const list = host.querySelector("details.lg-list")!;
    expect(list.closest("figure")).toBeNull();
    expect(list.querySelector("summary")!.textContent).toBe("Show the lineage as a list");
    const items = [...list.querySelectorAll("li")];
    expect(items.map((li) => li.textContent)).toEqual(links.map((l) => `${l.output} came from ${l.input}`));
    expect([...items[1].querySelectorAll("a")].map((a) => a.getAttribute("href"))).toEqual([href.asset(NAMED), href.asset(CUSTOMERS)]);
  });
});

describe("an asset's lineage graph", () => {
  test("stresses the asset, marks it as this page and names each line's pipeline", async () => {
    const host = await show(
      <LineageGraph
        links={[
          { input: DB, output: NAMED, pipeline: "orders-combine" },
          { input: NAMED, output: TOTALS, pipeline: "totals" },
        ]}
        centre={NAMED}
        label="Lineage of orders_named.csv"
      />,
    );
    const centre = box(host, NAMED);
    expect(centre.classList.contains("is-centre")).toBe(true);
    expect(centre.getAttribute("aria-current")).toBe("page");
    expect([...host.querySelectorAll(".lg-edge-label")].map((l) => l.textContent)).toEqual(["orders-combine", "totals"]);
    expect(host.querySelector(".lg-band")).toBeNull();
    expect(host.querySelector("figcaption")!.textContent).toContain("orders_named.csv came from 1 asset, and 1 asset was made from it.");
    expect(described(centre)).toBe("Came from public.shop_orders (pipeline orders-combine). Went into totals.parquet (pipeline totals).");
    expect(host.querySelector(`details.lg-list a[href='${href.pipeline("totals")}']`)).not.toBeNull();
  });

  test("says when the asset also came from itself", async () => {
    const host = await show(
      <LineageGraph
        links={[
          { input: DB, output: NAMED, pipeline: "p" },
          { input: NAMED, output: NAMED, pipeline: "dedupe" },
        ]}
        centre={NAMED}
        label="Lineage"
      />,
    );
    expect(host.querySelector("figcaption")!.textContent).toContain("orders_named.csv came from 1 asset and itself, and 0 assets were made from it.");
  });
});

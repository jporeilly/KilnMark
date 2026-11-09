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

describe("a run's lineage graph", () => {
  test("is a figure with a name and a caption saying what it holds", async () => {
    const host = await show(<LineageGraph links={links} pipeline="orders-combine" label="Lineage of run 7" />);
    const figure = host.querySelector("figure")!;
    const named = document.getElementById(figure.getAttribute("aria-labelledby")!)!;
    expect(named.textContent).toBe("Lineage of run 7");
    const caption = document.getElementById(figure.getAttribute("aria-describedby")!)!;
    expect(caption.tagName).toBe("FIGCAPTION");
    expect(caption.textContent).toContain("2 inputs went through the pipeline orders-combine into 2 outputs.");
    expect(host.querySelector(".lg-band")!.textContent).toBe("Pipeline orders-combine");
  });

  test("shows readable names, kinds and sources, with the URN only on hover", async () => {
    const host = await show(<LineageGraph links={links} pipeline="orders-combine" label="Lineage" />);
    const file = box(host, NAMED);
    expect(file.querySelector(".lg-name")!.textContent).toBe("orders_named.csv");
    expect(file.querySelector(".lg-folder")!.textContent).toBe("samples/combine/");
    expect(file.textContent).toContain("in starter-samples-files");
    expect(file.textContent).not.toContain("urn:");
    const table = box(host, DB);
    expect(table.querySelector(".lg-name")!.textContent).toBe("public.shop_orders");
    expect(table.textContent).toContain("Table");
    expect(table.textContent).toContain("in starter-samples-db");
  });

  test("each box is a link to its catalogue page", async () => {
    const host = await show(<LineageGraph links={links} pipeline="orders-combine" label="Lineage" />);
    expect(boxes(host).map((a) => a.getAttribute("href")).sort()).toEqual([DB, CUSTOMERS, NAMED, TOTALS].map((u) => href.asset(u)).sort());
    expect(box(host, NAMED).getAttribute("href")).toBe(href.asset(NAMED));
  });

  test("draws a line for each recorded pair only", async () => {
    const host = await show(<LineageGraph links={links} pipeline="orders-combine" label="Lineage" />);
    const lines = [...host.querySelectorAll("path.lg-line")].map((p) => [p.getAttribute("data-from"), p.getAttribute("data-to")]);
    expect(lines).toHaveLength(3);
    expect(lines).toContainEqual([CUSTOMERS, NAMED]);
    expect(lines).not.toContainEqual([CUSTOMERS, TOTALS]);
  });

  test("each box's link says in words what it came from and went into", async () => {
    const host = await show(<LineageGraph links={links} pipeline="orders-combine" label="Lineage" />);
    expect(box(host, NAMED).textContent).toContain("came from public.shop_orders and public.shop_customers.");
    expect(box(host, TOTALS).textContent).toContain("came from public.shop_orders.");
    expect(box(host, CUSTOMERS).textContent).toContain("went into orders_named.csv.");
  });

  test("focusing a box picks out its lines and fades the others", async () => {
    const host = await show(<LineageGraph links={links} pipeline="orders-combine" label="Lineage" />);
    await act(async () => box(host, CUSTOMERS).focus());
    const near = [...host.querySelectorAll("path.lg-line.is-near")].map((p) => p.getAttribute("data-from"));
    expect(near).toEqual([CUSTOMERS]);
    expect(host.querySelectorAll("path.lg-line.is-faded")).toHaveLength(2);
  });

  test("keeps the list of pairs, with its links, under Show the lineage as a list", async () => {
    const host = await show(<LineageGraph links={links} pipeline="orders-combine" label="Lineage" />);
    const list = host.querySelector("details.lg-list")!;
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
    expect(centre.textContent).toContain("came from public.shop_orders (pipeline orders-combine)");
    expect(host.querySelector("details.lg-list a[href='" + href.pipeline("totals") + "']")).not.toBeNull();
  });
});

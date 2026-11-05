// @vitest-environment jsdom
import { act } from "react";
import { createRoot } from "react-dom/client";
import { afterEach, describe, expect, test, vi } from "vitest";
import type { PipelineSummary, SampleCatalogue, Template } from "../api";

const pipeline = (name: string, library: "sample" | null): PipelineSummary => ({
  name,
  displayName: name,
  description: null,
  latestVersion: 1,
  valid: true,
  updatedBy: "dana",
  updatedAt: "2026-11-05T10:00:00Z",
  lastRun: null,
  library,
});

const catalogue: SampleCatalogue = {
  placeholders: [],
  tables: null,
  samples: [
    { file: "01-read-and-write/read-query.yaml", name: "sample-read-query", type: "pipeline", description: "A query", sources: [], exists: true },
    { file: "02-filter-and-route/filter.yaml", name: "sample-filter", type: "pipeline", description: "Filter", sources: [], exists: false },
    { file: "08-flows/passing-values.yaml", name: "sample-passing-values", type: "flow", description: "Values", sources: [], exists: false },
  ],
};

const templates: Template[] = [
  { file: "templates/copy-table.yaml", name: "template-copy-table", type: "pipeline", description: "Copy a table.", yaml: "name: x\n" },
  { file: "templates/run-in-order.yaml", name: "template-run-in-order", type: "flow", description: "In order.", yaml: "name: y\n" },
];

vi.mock("../api", async (original) => {
  const real = await original<typeof import("../api")>();
  return {
    ...real,
    pipelinesApi: { ...real.pipelinesApi, list: vi.fn(async () => [pipeline("orders", null), pipeline("sample-read-query", "sample")]) },
    flowsApi: { ...real.flowsApi, list: vi.fn(async () => []) },
    samplesApi: {
      ...real.samplesApi,
      list: vi.fn(async () => catalogue),
      offered: vi.fn(async () => ({ offered: true })),
      templates: vi.fn(async () => templates),
    },
    api: { ...real.api, sources: vi.fn(async () => []) },
  };
});

const { LibraryPage, groupOf, groupSamples } = await import("./LibraryPage");
const { PipelinesPage } = await import("../pipelines/PipelinesPage");

const host = document.createElement("div");
document.body.appendChild(host);
const root = createRoot(host);

afterEach(() => {
  act(() => root.render(<></>));
});

const settle = () =>
  act(async () => {
    await new Promise((r) => setTimeout(r, 0));
  });

describe("the library", () => {
  test("names a sample's group after its folder", () => {
    expect(groupOf("01-read-and-write/read-query.yaml")).toBe("Read and write");
    expect(groupOf("08-flows/passing-values.yaml")).toBe("Flows");
    expect(groupOf("loose.yaml")).toBe("Other");
    expect(groupSamples(catalogue.samples).map(([g, s]) => [g, s.length])).toEqual([
      ["Read and write", 1],
      ["Filter and route", 1],
      ["Flows", 1],
    ]);
  });

  test("lists the samples by folder, linking those added", async () => {
    await act(async () => root.render(<LibraryPage role="designer" tab="samples" />));
    await settle();
    const headings = [...host.querySelectorAll("section h2")].map((h) => h.textContent);
    expect(headings).toEqual(["Read and write", "Filter and route", "Flows"]);
    const link = [...host.querySelectorAll("a")].find((a) => a.textContent === "sample-read-query");
    expect(link?.getAttribute("href")).toBe("#/pipelines/sample-read-query");
    expect(host.textContent).toContain("Not added");
    expect(host.textContent).toContain("Add samples");
  });

  test("opens a template as a new pipeline or flow, for designers only", async () => {
    await act(async () => root.render(<LibraryPage role="designer" tab="templates" />));
    await settle();
    const uses = [...host.querySelectorAll("a")].filter((a) => a.textContent === "Use this template");
    expect(uses.map((a) => a.getAttribute("href"))).toEqual([
      "#/pipelines/new?template=templates%2Fcopy-table.yaml",
      "#/flows/new?template=templates%2Frun-in-order.yaml",
    ]);
    await act(async () => root.render(<LibraryPage role="viewer" tab="templates" />));
    await settle();
    expect([...host.querySelectorAll("a")].some((a) => a.textContent === "Use this template")).toBe(false);
  });

  test("the Pipelines page keeps samples off its list and points to the Library", async () => {
    await act(async () => root.render(<PipelinesPage role="designer" />));
    await settle();
    const rows = [...host.querySelectorAll("tbody th a")].map((a) => a.textContent);
    expect(rows).toEqual(["orders"]);
    expect(host.textContent).toContain("1 sample pipeline is in the Library");
  });
});

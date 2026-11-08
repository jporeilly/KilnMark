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
    {
      file: "01-read-and-write/read-query.yaml",
      name: "sample-read-query",
      type: "pipeline",
      description: "A query",
      sources: [],
      exists: true,
      explanation: {
        title: "Read with a query",
        shows: "Runs one SELECT.",
        why: "Banks often count in the database.",
        lookFor: "The run's reads.",
        needs: "A PostgreSQL crm-db with Allow SQL on.\nPOST /api/v1/samples",
      },
    },
    { file: "02-filter-and-route/filter.yaml", name: "sample-filter", type: "pipeline", description: "Filter", sources: [], exists: false,
      // only a title: nothing to open
      explanation: { title: "Filter", shows: null, why: null, lookFor: null, needs: null } },
    {
      file: "08-flows/passing-values.yaml",
      name: "sample-passing-values",
      type: "flow",
      description: "Values",
      sources: [],
      exists: false,
      explanation: { title: "Values", shows: "Passes values on.", why: null, lookFor: null, needs: null },
    },
  ],
};

const templates: Template[] = [
  {
    file: "templates/copy-table.yaml",
    name: "template-copy-table",
    type: "pipeline",
    description: "Copy a table.",
    yaml: "name: x\n",
    explanation: {
      title: "Template: copy a table",
      shows: "Copies one table.",
      why: "Data engineers often start here.",
      lookFor: "The mode.",
      needs: "A database.",
    },
  },
  { file: "templates/run-in-order.yaml", name: "template-run-in-order", type: "flow", description: "In order.", yaml: "name: y\n", explanation: null },
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

  test("on a production install says why there are no samples, and offers no way to add them", async () => {
    const { samplesApi, ApiError } = await import("../api");
    vi.mocked(samplesApi.list).mockRejectedValueOnce(
      new ApiError(
        "This is a production install: the samples are only on a Starter Pack install, for training and development",
        403,
      ),
    );
    await act(async () => root.render(<LibraryPage role="admin" tab="samples" />));
    await settle();
    expect(host.textContent).toContain("This is a production install: the samples are only on a Starter Pack install");
    expect(host.textContent).toContain("The templates are still here");
    expect(host.textContent).not.toContain("Add samples");
  });

  test("shows what each sample shows, why it matters, what to look for and what it needs", async () => {
    await act(async () => root.render(<LibraryPage role="viewer" tab="samples" />));
    await settle();
    const about = [...host.querySelectorAll("details.lib-about")];
    expect(about.map((d) => d.querySelector("summary")?.textContent)).toEqual([
      "About this sample: sample-read-query",
      "About this sample: sample-passing-values",
    ]);
    const parts = (d: Element) => [...d.querySelectorAll("dt")].map((dt) => [dt.textContent, dt.nextElementSibling?.textContent]);
    expect(parts(about[0]!)).toEqual([
      ["What it shows", "Runs one SELECT."],
      ["Why it matters", "Banks often count in the database."],
      ["Look for when it runs", "The run's reads."],
      ["Needs", "A PostgreSQL crm-db with Allow SQL on.\nPOST /api/v1/samples"],
    ]);
    // a part the comment leaves out is not shown
    expect(parts(about[1]!)).toEqual([["What it shows", "Passes values on."]]);
  });

  test("a template's card says what it is for, what to look for and what it needs", async () => {
    await act(async () => root.render(<LibraryPage role="viewer" tab="templates" />));
    await settle();
    const cards = [...host.querySelectorAll(".lib-card")];
    expect([...cards[0]!.querySelectorAll("dt")].map((dt) => dt.textContent)).toEqual(["What it is for", "Why it matters", "Look for", "Needs"]);
    expect(cards[0]!.textContent).toContain("Copies one table.");
    expect(cards[1]!.querySelector("dl")).toBeNull();
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

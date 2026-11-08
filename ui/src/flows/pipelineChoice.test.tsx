// @vitest-environment jsdom
import { act } from "react";
import { createRoot } from "react-dom/client";
import { afterEach, describe, expect, test, vi } from "vitest";
import type { PipelineSummary } from "../api";

const summary = (name: string): PipelineSummary => ({
  name,
  displayName: name,
  description: null,
  latestVersion: 1,
  valid: true,
  updatedBy: "dana",
  updatedAt: "2026-11-05T10:00:00Z",
  lastRun: null,
  library: "sample",
});

vi.mock("../api", async (original) => {
  const real = await original<typeof import("../api")>();
  return {
    ...real,
    pipelinesApi: {
      ...real.pipelinesApi,
      list: vi.fn(async () => [summary("sample-tables"), summary("sample-join"), summary("sample-sql-before-and-after")]),
    },
  };
});

const { FlowSettings, usePipelineNames } = await import("./FlowCanvasTools");
const { readFlowGraph } = await import("./graph");

const host = document.createElement("div");
document.body.appendChild(host);
const root = createRoot(host);
afterEach(() => act(() => root.render(<></>)));

const FLOW = `kilnmark: flow/v1
name: demo
nodes:
  - id: load
    type: run-pipeline
    pipeline: sample-join
`;

function Tools() {
  const pipelines = usePipelineNames(true);
  const r = readFlowGraph(FLOW);
  if (!r.ok || !pipelines) return null;
  return <FlowSettings graph={r.graph} text={FLOW} pipelines={pipelines} selected={{ node: "load" }} onSelect={() => {}} apply={() => true} />;
}

describe("the Pipeline choice on a flow's node", () => {
  test("offers the team's pipelines, never Kilnmark's own sample-tables, with room for long names", async () => {
    await act(async () => root.render(<Tools />));
    await act(async () => {
      await new Promise((r) => setTimeout(r, 0));
    });
    const select = [...host.querySelectorAll("select")].find((s) => [...s.options].some((o) => o.value === "sample-join"))!;
    expect([...select.options].map((o) => o.value)).toEqual(["sample-join", "sample-sql-before-and-after"]);
    // Spans two columns of the settings form, as a message does.
    expect(select.closest(".field")!.classList.contains("fd-field-long")).toBe(true);
  });
});

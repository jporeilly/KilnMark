import { describe, expect, test } from "vitest";
import type { NodeType } from "../../api";
import { autoLayout } from "./layout";
import { readGraph, type Catalogue } from "./model";

const type = (t: string, kind: string, inputs: string[], outputs: string[]) =>
  ({ type: t, kind, label: t, help: "", inputs, manyInputs: [], outputs, settings: [] }) as unknown as NodeType;

const catalogue: Catalogue = new Map([
  ["read", type("read", "source", [], ["out"])],
  ["route", type("route", "transform", ["in"], ["out"])],
  ["filter", type("filter", "transform", ["in"], ["out", "rejected"])],
  ["write", type("write", "sink", ["in"], [])],
]);

const graph = (yaml: string) => {
  const r = readGraph(yaml, catalogue);
  if (!r.ok) throw new Error(r.error);
  return r.graph;
};

/** The ids of a column, top to bottom. */
const topToBottom = (at: Record<string, { x: number; y: number }>, ids: string[]) =>
  [...ids].sort((a, b) => at[a].y - at[b].y);

describe("auto-layout keeps connections from crossing", () => {
  test("a route's targets are stacked in the order of its ports (as the sample route writes them)", () => {
    // The route's ports, top to bottom: large, bulk, then out (what fits no route).
    const g = graph(`kilnmark: pipeline/v1
name: demo
nodes:
  - id: lines
    type: read
  - id: by_size
    type: route
    input: lines
    routes: { large: "x > 1", bulk: "y > 1" }
  - id: large
    type: write
    input: by_size.large
  - id: bulk
    type: write
    input: by_size.bulk
  - id: rest
    type: write
    input: by_size
`);
    const at = autoLayout(g, catalogue);
    expect(topToBottom(at, ["rest", "bulk", "large"])).toEqual(["large", "bulk", "rest"]);
  });

  test("a filter's kept rows go above its rejected ones, whichever is written first", () => {
    const g = graph(`kilnmark: pipeline/v1
name: demo
nodes:
  - id: rows
    type: read
  - id: best
    type: filter
    input: rows
  - id: others
    type: write
    input: best.rejected
  - id: kept
    type: write
    input: best
`);
    const at = autoLayout(g, catalogue);
    expect(topToBottom(at, ["others", "kept"])).toEqual(["kept", "others"]);
    // Stacked without overlapping, in the same column.
    expect(at.kept.x).toBe(at.others.x);
    expect(at.others.y).toBeGreaterThan(at.kept.y);
  });
});

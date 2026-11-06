import { describe, expect, test } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { PredictedSection } from "./PredictedSection";
import type { PredictedQuality } from "../prediction";

const p = (rows: number | null): PredictedQuality => ({
  node: "out",
  role: "output",
  asset: null,
  overall: null,
  dimensions: {},
  reasons: [],
  unknown: [],
  assumptions: [],
  rows,
  gate: null,
});

describe("the predicted quality reads as sentences", () => {
  test("the rows sentence starts with a capital after the full stop", () => {
    expect(renderToStaticMarkup(<PredictedSection p={p(662)} id="q" />)).toMatch(/no data was read\. About 662 rows/);
    expect(renderToStaticMarkup(<PredictedSection p={p(null)} id="q" />)).toContain("no data was read. Rows unknown.");
  });
});

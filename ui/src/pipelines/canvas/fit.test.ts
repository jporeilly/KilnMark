import { describe, expect, test } from "vitest";
import { fitBesideMap, overlaps, type Area, type Box } from "./fit";

/** Where the nodes are drawn on the canvas at a viewport. */
const drawn = (b: Box, v: { x: number; y: number; zoom: number }): Box => ({
  x: v.x + b.x * v.zoom,
  y: v.y + b.y * v.zoom,
  width: b.width * v.zoom,
  height: b.height * v.zoom,
});

/** React Flow's map: 200 by 150, 15px in from the bottom-right corner. */
const mapIn = (a: Area): Box => ({ x: a.left + a.width - 215, y: a.top + a.height - 165, width: 200, height: 150 });

describe("Fit leaves the overview map clear", () => {
  // A pipeline about as tall as it is wide (sample-gate-quarantine, sample-sort) on a tall canvas.
  const nodes: Box = { x: 24, y: 24, width: 980, height: 900 };
  const tall: Area = { left: 0, top: 0, width: 960, height: 1000 };

  test("the nodes never go under the map", () => {
    const map = mapIn(tall);
    const v = fitBesideMap(nodes, tall, map);
    const d = drawn(nodes, v);
    expect(overlaps(d, map)).toBe(false);
    // and still inside the canvas
    expect(d.x).toBeGreaterThanOrEqual(tall.left);
    expect(d.y).toBeGreaterThanOrEqual(tall.top);
    expect(d.x + d.width).toBeLessThanOrEqual(tall.left + tall.width);
    expect(d.y + d.height).toBeLessThanOrEqual(tall.top + tall.height);
  });

  test("beside the node list too, and wide pipelines on wide canvases", () => {
    const beside: Area = { left: 260, top: 0, width: 1100, height: 760 };
    for (const b of [nodes, { x: 0, y: 0, width: 2400, height: 700 }, { x: -40, y: 10, width: 500, height: 1600 }]) {
      const map = mapIn(beside);
      expect(overlaps(drawn(b, fitBesideMap(b, beside, map)), map)).toBe(false);
    }
  });

  test("a pipeline the map would not cover is fitted as before, centred in the whole canvas", () => {
    const small: Box = { x: 0, y: 0, width: 400, height: 200 };
    const v = fitBesideMap(small, tall, mapIn(tall));
    expect(v.zoom).toBe(1);
    expect(v).toEqual(fitBesideMap(small, tall, null));
  });
});

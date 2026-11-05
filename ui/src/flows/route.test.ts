import { describe, expect, test } from "vitest";
import { roundedPath, steps } from "./route";

// Two columns of nodes: 0-212 and 300-512, with a gap (where labels go) between them.
const COLUMNS: [number, number][] = [
  [0, 212],
  [300, 512],
  [600, 812],
];

describe("steps", () => {
  test("between neighbouring columns: one step, turning at the label in the gap", () => {
    const route = [
      { x: 212, y: 50 },
      { x: 256, y: 120 },
      { x: 300, y: 150 },
    ];
    expect(steps(route, COLUMNS)).toEqual([
      { x: 212, y: 50 },
      { x: 256, y: 50 },
      { x: 256, y: 150 },
      { x: 300, y: 150 },
    ]);
  });

  test("level ends stay one straight line", () => {
    expect(
      steps(
        [
          { x: 212, y: 50 },
          { x: 256, y: 50 },
          { x: 300, y: 50 },
        ],
        COLUMNS,
      ),
    ).toEqual([
      { x: 212, y: 50 },
      { x: 300, y: 50 },
    ]);
  });

  test("past a column: up in the gap, across above its nodes, down in the next gap; never upright inside a column", () => {
    const route = [
      { x: 212, y: 100 },
      { x: 256, y: -20 },
      { x: 406, y: -20 },
      { x: 556, y: -20 },
      { x: 600, y: 130 },
    ];
    const out = steps(route, COLUMNS);
    expect(out).toEqual([
      { x: 212, y: 100 },
      { x: 256, y: 100 },
      { x: 256, y: -20 },
      { x: 556, y: -20 },
      { x: 556, y: 130 },
      { x: 600, y: 130 },
    ]);
    for (let i = 1; i < out.length; i++) {
      const upright = out[i - 1].x === out[i].x;
      if (upright) expect(COLUMNS.some(([l, r]) => out[i].x > l && out[i].x < r)).toBe(false);
    }
  });

  test("a second connection between the same two nodes runs at its label's height, bracketed", () => {
    const route = [
      { x: 212, y: 100 },
      { x: 256, y: 60 },
      { x: 300, y: 100 },
    ];
    expect(steps(route, COLUMNS, 20)).toEqual([
      { x: 212, y: 100 },
      { x: 236, y: 100 },
      { x: 236, y: 60 },
      { x: 276, y: 60 },
      { x: 276, y: 100 },
      { x: 300, y: 100 },
    ]);
  });

  test("never turns on a connector, even when no bend lies in a gap", () => {
    const out = steps(
      [
        { x: 212, y: 0 },
        { x: 300, y: 80 },
      ],
      COLUMNS,
    );
    expect(out).toEqual([
      { x: 212, y: 0 },
      { x: 256, y: 0 },
      { x: 256, y: 80 },
      { x: 300, y: 80 },
    ]);
  });
});

describe("roundedPath", () => {
  test("rounds each corner, less where a leg is short", () => {
    expect(
      roundedPath(
        [
          { x: 0, y: 0 },
          { x: 20, y: 0 },
          { x: 20, y: 6 },
          { x: 40, y: 6 },
        ],
        8,
      ),
    ).toBe("M 0 0 L 17 0 Q 20 0 20 3 L 20 3 Q 20 6 23 6 L 40 6");
  });

  test("a straight line has no corners", () => {
    expect(
      roundedPath(
        [
          { x: 0, y: 0 },
          { x: 50, y: 0 },
        ],
        8,
      ),
    ).toBe("M 0 0 L 50 0");
  });
});

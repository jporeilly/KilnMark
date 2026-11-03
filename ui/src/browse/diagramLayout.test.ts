import { describe, expect, test } from "vitest";
import { layoutBoxes, type Box } from "./diagramLayout";

const box = (id: string, height = 200): Box => ({ id, width: 260, height });

/** The drawing's width over its height. */
function shape(boxes: Box[], at: Map<string, { x: number; y: number }>) {
  const right = Math.max(...boxes.map((b) => at.get(b.id)!.x + b.width));
  const bottom = Math.max(...boxes.map((b) => at.get(b.id)!.y + b.height));
  return right / bottom;
}

describe("a schema diagram's layout", () => {
  test("tables that do not join are set out in rows, not one column", () => {
    const boxes = Array.from({ length: 9 }, (_, i) => box(`t${i}`));
    const at = layoutBoxes(boxes, []);
    expect(new Set([...at.values()].map((p) => p.y)).size).toBeLessThanOrEqual(3);
    expect(shape(boxes, at)).toBeGreaterThan(1);
  });

  test("many tables referring to one are laid out wide, not as one tall rank", () => {
    const boxes = [box("customers", 300), ...Array.from({ length: 8 }, (_, i) => box(`r${i}`))];
    const edges = boxes.slice(1).map((b): [string, string] => [b.id, "customers"]);
    const at = layoutBoxes(boxes, edges);
    expect(shape(boxes, at)).toBeGreaterThan(1);
  });

  test("no two boxes overlap", () => {
    const boxes = [box("a"), box("b", 400), box("c"), box("d"), box("e", 120), box("f")];
    const at = layoutBoxes(boxes, [
      ["a", "b"],
      ["c", "b"],
      ["e", "f"],
    ]);
    for (const p of boxes)
      for (const q of boxes) {
        if (p.id >= q.id) continue;
        const a = at.get(p.id)!;
        const b = at.get(q.id)!;
        const apart = a.x + p.width <= b.x || b.x + q.width <= a.x || a.y + p.height <= b.y || b.y + q.height <= a.y;
        expect(apart, `${p.id} and ${q.id}`).toBe(true);
      }
  });
});

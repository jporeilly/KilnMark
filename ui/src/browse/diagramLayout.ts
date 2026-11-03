/**
 * Where a schema diagram's boxes go: each group of joined tables laid out with dagre, across (LR) or
 * down (TB), whichever is closer to the frame's shape, then the groups (single tables too) packed in
 * rows across the width. One dagre pass over everything put every table referring to one target in
 * one rank, a column many tables tall that fitting shrank to nothing. Pure, unit tested.
 */
import { Graph, layout } from "@dagrejs/dagre";

export type Box = { id: string; width: number; height: number };
export type Point = { x: number; y: number };

const GAP = 48;

/** The groups of boxes joined by edges, each in the boxes' order. */
function components(boxes: Box[], edges: [string, string][]): Box[][] {
  const parent = new Map(boxes.map((b) => [b.id, b.id]));
  const find = (id: string): string => {
    let p = parent.get(id)!;
    while (p !== parent.get(p)) p = parent.get(p)!;
    parent.set(id, p);
    return p;
  };
  for (const [a, b] of edges) {
    if (!parent.has(a) || !parent.has(b)) continue;
    const ra = find(a);
    const rb = find(b);
    if (ra !== rb) parent.set(ra, rb);
  }
  const groups = new Map<string, Box[]>();
  for (const b of boxes) {
    const root = find(b.id);
    groups.set(root, [...(groups.get(root) ?? []), b]);
  }
  return [...groups.values()];
}

type Placed = { width: number; height: number; at: Map<string, Point> };

/** One group laid out by dagre in one direction, from its top left. */
function dagreLayout(group: Box[], edges: [string, string][], rankdir: "LR" | "TB"): Placed {
  const g = new Graph();
  g.setGraph({ rankdir, nodesep: 28, ranksep: 90, marginx: 0, marginy: 0 });
  g.setDefaultEdgeLabel(() => ({}));
  const ids = new Set(group.map((b) => b.id));
  for (const b of group) g.setNode(b.id, { width: b.width, height: b.height });
  for (const [a, b] of edges) if (ids.has(a) && ids.has(b) && a !== b) g.setEdge(a, b);
  layout(g);
  const at = new Map<string, Point>();
  let width = 0;
  let height = 0;
  for (const b of group) {
    const n = g.node(b.id);
    const p = { x: n.x - b.width / 2, y: n.y - b.height / 2 };
    at.set(b.id, p);
    width = Math.max(width, p.x + b.width);
    height = Math.max(height, p.y + b.height);
  }
  return { width, height, at };
}

/** How far a shape is from the aspect wanted, either way. */
const misfit = (p: Placed, aspect: number) => Math.abs(Math.log(p.width / Math.max(1, p.height) / aspect));

/**
 * Each box's top left. {@code aspect} is the frame's width over its height: groups take the direction
 * that fits it best, and rows are as wide as a box of that shape holding everything.
 */
export function layoutBoxes(boxes: Box[], edges: [string, string][], aspect = 2): Map<string, Point> {
  const groups = components(boxes, edges).map((group) => {
    if (group.length === 1) {
      const b = group[0];
      return { width: b.width, height: b.height, at: new Map([[b.id, { x: 0, y: 0 }]]), size: 1 };
    }
    const across = dagreLayout(group, edges, "LR");
    const down = dagreLayout(group, edges, "TB");
    return { ...(misfit(across, aspect) <= misfit(down, aspect) ? across : down), size: group.length };
  });
  // Joined groups first, largest first; single tables after them, in their order.
  groups.sort((a, b) => (b.size > 1 ? 1 : 0) - (a.size > 1 ? 1 : 0) || (a.size > 1 ? b.height - a.height : 0));
  const area = groups.reduce((s, g) => s + (g.width + GAP) * (g.height + GAP), 0);
  const rowWidth = Math.max(Math.sqrt(area * aspect), ...groups.map((g) => g.width));
  const out = new Map<string, Point>();
  let x = 0;
  let y = 0;
  let rowHeight = 0;
  for (const g of groups) {
    if (x > 0 && x + g.width > rowWidth) {
      x = 0;
      y += rowHeight + GAP;
      rowHeight = 0;
    }
    for (const [id, p] of g.at) out.set(id, { x: x + p.x, y: y + p.y });
    x += g.width + GAP;
    rowHeight = Math.max(rowHeight, g.height);
  }
  return out;
}

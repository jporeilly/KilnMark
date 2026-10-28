import { Graph as Dagre, layout } from "@dagrejs/dagre";
import { portsOf, type Catalogue, type Graph, type Point } from "./model";

export const NODE_WIDTH = 212;
const HEAD = 86;
const PORT = 22;
/** One row of badges under the ports (a chip is 20px high, with 4px between rows). */
export const BADGE_ROW = 24;
/** The space under the last row of badges, less the gap the last row does not have. */
const FOOT = 2;
/** The least vertical space between two steps in the same column: room for a connection's label. */
export const GAP_Y = 36;
/** The space either side of a connection's label, between it and the steps it joins. */
const LABEL_MARGIN = 20;

/**
 * Which rows of badges a step shows under its ports: problems and the run's rows and outcome;
 * its measured and predicted quality (one row each: at a step's width they do not fit side by
 * side); and, for a gate, what it did with the rows. Each row is one line high (chips do not wrap
 * inside), so the step's height follows from its ports and these rows.
 */
export type BadgeRows = { first: boolean; measured: boolean; predicted: boolean; gate: boolean };

export const NO_BADGES: BadgeRows = { first: false, measured: false, predicted: false, gate: false };

export const badgeRowCount = (b: BadgeRows) => Number(b.first) + Number(b.measured) + Number(b.predicted) + Number(b.gate);

/** A step's height on the canvas: its head, its ports and the rows of badges it shows. */
export const nodeHeight = (ports: { inputs: string[]; outputs: string[] }, badges: BadgeRows = NO_BADGES) => {
  const rows = badgeRowCount(badges);
  // The ports' padding (8) and the step's border (2), then the rows of chips.
  return HEAD + PORT * Math.max(ports.inputs.length, ports.outputs.length, 1) + 10 + (rows ? rows * BADGE_ROW + FOOT : 0);
};

/** About how wide a connection's label is drawn (11px semibold, with its background's padding). */
export const labelWidth = (text: string) => (text ? Math.ceil(text.length * 6.6) + 12 : 0);

/** A connection's label, for spacing: from and to step ids and its words. */
export type EdgeLabel = { from: string; to: string; text: string };

export type LayoutSizes = {
  /** Each step's height, when it is not what its ports alone give (badges after a run, estimates). */
  heights?: Record<string, number>;
  /** The connections' labels, so the space between columns fits them. */
  labels?: EdgeLabel[];
};

const heightOf = (graph: Graph, catalogue: Catalogue, sizes: LayoutSizes | undefined, id: string) => {
  const s = graph.steps.find((x) => x.id === id);
  return sizes?.heights?.[id] ?? (s ? nodeHeight(portsOf(s, catalogue)) : nodeHeight({ inputs: [], outputs: [] }));
};

/**
 * Layered, left to right: every step placed by dagre from the edges, at its real height, with the
 * columns far enough apart for the widest connection label between them.
 */
export function autoLayout(graph: Graph, catalogue: Catalogue, sizes?: LayoutSizes): Record<string, Point> {
  const g = new Dagre();
  const widest = Math.max(0, ...(sizes?.labels ?? []).map((l) => labelWidth(l.text)));
  g.setGraph({ rankdir: "LR", nodesep: GAP_Y, ranksep: Math.max(64, widest + 2 * LABEL_MARGIN), marginx: 24, marginy: 24 });
  g.setDefaultEdgeLabel(() => ({}));
  const ids = new Set(graph.steps.map((s) => s.id));
  for (const s of graph.steps) g.setNode(s.id, { width: NODE_WIDTH, height: heightOf(graph, catalogue, sizes, s.id) });
  for (const e of graph.edges) if (ids.has(e.from) && ids.has(e.to)) g.setEdge(e.from, e.to);
  layout(g);
  const out: Record<string, Point> = {};
  for (const s of graph.steps) {
    const n = g.node(s.id);
    out[s.id] = { x: Math.round(n.x - n.width / 2), y: Math.round(n.y - n.height / 2) };
  }
  return out;
}

/**
 * Where each step is drawn: its saved position, else where a layered layout puts it. Steps without
 * a saved position among steps that have one are placed below the others, so they never land on top
 * of a step that was placed by hand.
 */
export function placed(graph: Graph, catalogue: Catalogue, sizes?: LayoutSizes): Record<string, Point> {
  const saved = graph.positions;
  const missing = graph.steps.filter((s) => !saved[s.id]);
  if (missing.length === 0) return { ...saved };
  const auto = autoLayout(graph, catalogue, sizes);
  if (missing.length === graph.steps.length) return auto;
  const out: Record<string, Point> = {};
  let bottom = 0;
  for (const s of graph.steps) {
    const p = saved[s.id];
    if (p) {
      out[s.id] = p;
      bottom = Math.max(bottom, p.y + heightOf(graph, catalogue, sizes, s.id));
    }
  }
  const top = Math.min(...missing.map((s) => auto[s.id].y));
  for (const s of missing) out[s.id] = { x: auto[s.id].x, y: auto[s.id].y - top + bottom + 60 };
  return out;
}

/**
 * Where the steps are shown, from where they are placed: moved apart, in the view only, where
 * their real heights (badges after a run, estimates) or their connections' labels would not fit.
 * The saved layout is not changed. Two passes:
 *
 *   1. Across: where a labelled connection goes right to a step whose gap from its source is
 *      narrower than the label, that step and every step at or right of it move right by the
 *      difference, so the label sits in the gap rather than under a step.
 *   2. Down: in reading order (top to bottom, then left to right), a step that overlaps one above
 *      it in the same column, or comes closer than GAP_Y, moves down below it.
 *
 * Steps only ever move right or down, so the order of the picture stays as it was laid out. Pure.
 */
export function makeRoom(placedAt: Record<string, Point>, heights: Record<string, number>, labels: EdgeLabel[] = []): Record<string, Point> {
  const out: Record<string, Point> = {};
  for (const [id, p] of Object.entries(placedAt)) out[id] = { x: p.x, y: p.y };
  const ids = Object.keys(out);
  const h = (id: string) => heights[id] ?? nodeHeight({ inputs: [], outputs: [] });

  // 1. Across, left to right by source.
  const forward = labels
    .filter((l) => l.text && out[l.from] && out[l.to])
    .sort((a, b) => out[a.from].x - out[b.from].x || out[a.to].x - out[b.to].x);
  for (const l of forward) {
    const s = out[l.from];
    const t = out[l.to];
    // Only connections going right, to a step in a later column.
    if (t.x < s.x + NODE_WIDTH / 2) continue;
    const need = labelWidth(l.text) + 2 * LABEL_MARGIN;
    const gap = t.x - (s.x + NODE_WIDTH);
    if (gap >= need) continue;
    const by = need - gap;
    const from = t.x - NODE_WIDTH / 2;
    for (const id of ids) if (id !== l.from && out[id].x >= from) out[id].x += by;
  }

  // 2. Down, in reading order.
  const order = [...ids].sort((a, b) => out[a].y - out[b].y || out[a].x - out[b].x);
  const done: string[] = [];
  for (const id of order) {
    const p = out[id];
    for (let pass = 0; pass < order.length; pass++) {
      let moved = false;
      for (const o of done) {
        const q = out[o];
        const sameColumn = Math.abs(p.x - q.x) < NODE_WIDTH + 8;
        const clash = p.y < q.y + h(o) + GAP_Y && q.y < p.y + h(id) + GAP_Y;
        // q comes first in reading order, so p goes below it, even when q was itself moved down.
        if (sameColumn && clash) {
          p.y = q.y + h(o) + GAP_Y;
          moved = true;
        }
      }
      if (!moved) break;
    }
    done.push(id);
  }
  return out;
}

/** A free spot to the right of `from` (or of everything), not on top of another step. */
export function spotAfter(positions: Record<string, Point>, from: string | null): Point {
  const all = Object.values(positions);
  const base = from && positions[from] ? { x: positions[from].x + NODE_WIDTH + 70, y: positions[from].y } : null;
  let at = base ?? { x: all.length ? Math.max(...all.map((p) => p.x)) + NODE_WIDTH + 70 : 40, y: all.length ? Math.min(...all.map((p) => p.y)) : 40 };
  const clash = (p: Point) => all.some((q) => Math.abs(q.x - p.x) < NODE_WIDTH - 20 && Math.abs(q.y - p.y) < 100);
  for (let i = 0; i < 20 && clash(at); i++) at = { x: at.x, y: at.y + 140 };
  return at;
}

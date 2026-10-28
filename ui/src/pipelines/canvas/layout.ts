import { Graph as Dagre, layout } from "@dagrejs/dagre";
import { portsOf, type Catalogue, type Graph, type Point } from "./model";

export const NODE_WIDTH = 212;
const HEAD = 86;
const PORT = 22;

export const nodeHeight = (ports: { inputs: string[]; outputs: string[] }) =>
  HEAD + PORT * Math.max(ports.inputs.length, ports.outputs.length, 1) + 8;

/** Layered, left to right: every step placed by dagre from the edges. */
export function autoLayout(graph: Graph, catalogue: Catalogue): Record<string, Point> {
  const g = new Dagre();
  g.setGraph({ rankdir: "LR", nodesep: 36, ranksep: 64, marginx: 24, marginy: 24 });
  g.setDefaultEdgeLabel(() => ({}));
  const ids = new Set(graph.steps.map((s) => s.id));
  for (const s of graph.steps) g.setNode(s.id, { width: NODE_WIDTH, height: nodeHeight(portsOf(s, catalogue)) });
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
export function placed(graph: Graph, catalogue: Catalogue): Record<string, Point> {
  const saved = graph.positions;
  const missing = graph.steps.filter((s) => !saved[s.id]);
  if (missing.length === 0) return { ...saved };
  const auto = autoLayout(graph, catalogue);
  if (missing.length === graph.steps.length) return auto;
  const out: Record<string, Point> = {};
  let bottom = 0;
  for (const s of graph.steps) {
    const p = saved[s.id];
    if (p) {
      out[s.id] = p;
      bottom = Math.max(bottom, p.y + nodeHeight(portsOf(s, catalogue)));
    }
  }
  const top = Math.min(...missing.map((s) => auto[s.id].y));
  for (const s of missing) out[s.id] = { x: auto[s.id].x, y: auto[s.id].y - top + bottom + 60 };
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

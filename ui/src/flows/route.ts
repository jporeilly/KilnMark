/**
 * How a laid-out connection is drawn on a flow's diagram (FlowDiagram): in steps, as on a pipeline's
 * canvas, from a node's `out` connector to another's `in`, through the route the layout found. Pure,
 * unit tested.
 */
import type { Point } from "./graph";

/** The left and right of each column of nodes. */
export type Columns = [number, number][];

/** Whether `x` is within a column of nodes, not in a gap between columns. */
export const inColumn = (x: number, columns: Columns) => columns.some(([left, right]) => x > left && x < right);

/**
 * The corners of a connection drawn in steps through `points`: its start, the layout's bends, and its
 * end. Each change of height turns in a gap between columns of nodes, at a bend the layout put
 * there, so no upright crosses a node; never at the start or end, which are the connectors on the
 * nodes' sides. Runs across a column are at the height the layout gave them, clear of its nodes.
 *
 * A bend the route goes up (or down) to and straight back from, as the layout puts the label of a
 * second connection between the same two nodes, becomes a bracket `bracket` either side of it, so
 * the connection runs at its own height past its label rather than on top of the first.
 */
export function steps(points: Point[], columns: Columns, bracket = 0): Point[] {
  if (points.length < 2) return points;
  const out: Point[] = [points[0]];
  const last = points.length - 1;
  for (let i = 1; i <= last; i++) {
    const a = points[i - 1];
    const b = points[i];
    const c = points[i + 1];
    const bump = i < last && c && Math.abs(a.y - c.y) < 0.5 && Math.abs(a.y - b.y) >= 0.5 && !inColumn(b.x, columns);
    if (bump) {
      out.push({ x: b.x - bracket, y: a.y }, { x: b.x - bracket, y: b.y }, { x: b.x + bracket, y: b.y }, { x: b.x + bracket, y: c.y });
      out.push(c);
      i++;
      continue;
    }
    if (Math.abs(a.y - b.y) >= 0.5) {
      const aTurns = i - 1 > 0 && !inColumn(a.x, columns);
      const bTurns = i < last && !inColumn(b.x, columns);
      const x = aTurns ? a.x : bTurns ? b.x : (a.x + b.x) / 2;
      out.push({ x, y: a.y }, { x, y: b.y });
    }
    out.push(b);
  }
  // Drop repeats, and corners that are not corners (three in a line).
  const kept: Point[] = [];
  for (const p of out) {
    const prev = kept[kept.length - 1];
    if (prev && Math.abs(prev.x - p.x) < 0.5 && Math.abs(prev.y - p.y) < 0.5) continue;
    const before = kept[kept.length - 2];
    if (before && prev && ((Math.abs(before.x - prev.x) < 0.5 && Math.abs(prev.x - p.x) < 0.5) || (Math.abs(before.y - prev.y) < 0.5 && Math.abs(prev.y - p.y) < 0.5))) {
      kept[kept.length - 1] = p;
      continue;
    }
    kept.push(p);
  }
  return kept;
}

/** An SVG path along `corners`, each corner rounded by up to `radius` (less where a leg is short). */
export function roundedPath(corners: Point[], radius: number): string {
  if (corners.length === 0) return "";
  let d = `M ${corners[0].x} ${corners[0].y}`;
  for (let i = 1; i < corners.length - 1; i++) {
    const [a, v, b] = [corners[i - 1], corners[i], corners[i + 1]];
    const inLen = Math.hypot(v.x - a.x, v.y - a.y);
    const outLen = Math.hypot(b.x - v.x, b.y - v.y);
    const r = Math.min(radius, inLen / 2, outLen / 2);
    const from = { x: v.x - ((v.x - a.x) / (inLen || 1)) * r, y: v.y - ((v.y - a.y) / (inLen || 1)) * r };
    const to = { x: v.x + ((b.x - v.x) / (outLen || 1)) * r, y: v.y + ((b.y - v.y) / (outLen || 1)) * r };
    d += ` L ${from.x} ${from.y} Q ${v.x} ${v.y} ${to.x} ${to.y}`;
  }
  const end = corners[corners.length - 1];
  return corners.length > 1 ? `${d} L ${end.x} ${end.y}` : d;
}

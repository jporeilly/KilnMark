/**
 * Lineage as a graph: readable names from asset URNs, the recorded "came from" pairs as edges, and
 * where each box goes, laid out left to right with dagre. Pure, unit tested; LineageGraph.tsx draws
 * it. Only recorded pairs become edges: nothing is joined that a run did not record.
 */
import { Graph, layout } from "@dagrejs/dagre";

/** One recorded pair: `output` came from `input`, written by `node` of `pipeline` when known. */
export type LineageLink = { input: string; output: string; pipeline?: string | null; node?: string | null };

/** What a box shows of an asset, worked out from its URN alone. */
export type AssetName = {
  urn: string;
  /** The source's short name, empty when the URN could not be read. */
  source: string;
  /** A file has a path with a folder or a file's extension; anything else in a source is a table. */
  kind: "table" | "file" | "asset";
  /** The table's path (schema.table) or the file's own name. */
  name: string;
  /** The file's folder, without the trailing slash; null for a table or a file at the root. */
  folder: string | null;
  /** The file's extension in lower case, for its icon; null when there is none. */
  format: string | null;
};

const PREFIX = "urn:kilnmark:";

/** Extensions that make a path without a folder a file, not a table such as public.orders. */
const FILE_EXTENSIONS = new Set([
  "csv", "tsv", "txt", "json", "jsonl", "ndjson", "parquet", "avro", "orc", "xml", "yaml", "yml",
  "xlsx", "xls", "xlsm", "ods", "pdf", "doc", "docx", "ppt", "pptx", "odt", "rtf", "html", "htm",
  "md", "eml", "msg", "zip", "gz", "arrow", "feather",
]);

/** The catalogue escapes # and % in a file's path (%23, %25); undone in one pass for showing. */
const unescapePath = (path: string) => path.replace(/%2[35]/gi, (m) => (m === "%23" ? "#" : "%"));

const extension = (fileName: string) => fileName.split("#")[0].match(/\.([a-z0-9]+)$/i)?.[1]?.toLowerCase() ?? null;

/**
 * A readable name for an asset URN, `urn:kilnmark:<source>:<path>`. The source never holds a colon;
 * the path may. A path with a slash is a file in a folder; one without is a file when it ends in a
 * file's extension, else a table, unless `kind` says which (the catalogue knows, for an asset page's own
 * asset). A URN that does not read so is shown whole.
 */
export function assetName(urn: string, kind?: "table" | "file"): AssetName {
  const unread: AssetName = { urn, source: "", kind: "asset", name: urn, folder: null, format: null };
  if (!urn.startsWith(PREFIX)) return unread;
  const rest = urn.slice(PREFIX.length);
  const colon = rest.indexOf(":");
  if (colon <= 0 || colon === rest.length - 1) return unread;
  const source = rest.slice(0, colon);
  const path = rest.slice(colon + 1);
  const slash = path.lastIndexOf("/");
  if (kind === "table") return { urn, source, kind: "table", name: path, folder: null, format: null };
  if (slash >= 0) {
    const file = unescapePath(path.slice(slash + 1));
    const folder = unescapePath(path.slice(0, slash));
    if (!file) return { urn, source, kind: "file", name: unescapePath(path), folder: null, format: null };
    return { urn, source, kind: "file", name: file, folder: folder || null, format: extension(file) };
  }
  const shown = unescapePath(path);
  const ext = extension(shown);
  if (kind === "file" || (ext && FILE_EXTENSIONS.has(ext))) return { urn, source, kind: "file", name: shown, folder: null, format: ext };
  return { urn, source, kind: "table", name: path, folder: null, format: null };
}

/** One drawn edge: every recorded pair from one asset to another, with the pipelines that recorded it. */
export type LineageEdge = { from: string; to: string; pipelines: string[]; nodes: string[] };

export type LineageModel = {
  /** Every asset named in a pair, in the order first named. */
  assets: AssetName[];
  /** Each recorded pair once; never a pair that was not recorded. */
  edges: LineageEdge[];
  /** Assets recorded as coming from themselves (a write that read its own target). */
  fromItself: string[];
};

/**
 * The graph of the recorded pairs: each pair once, an asset that came from itself set apart.
 * `kinds` gives the kind of assets the caller knows, instead of guessing from the URN.
 */
export function lineageModel(links: LineageLink[], kinds: Record<string, "table" | "file"> = {}): LineageModel {
  const assets = new Map<string, AssetName>();
  const edges = new Map<string, LineageEdge>();
  const fromItself: string[] = [];
  const add = (list: string[], value: string | null | undefined) => {
    if (value && !list.includes(value)) list.push(value);
  };
  for (const l of links) {
    for (const urn of [l.input, l.output]) if (!assets.has(urn)) assets.set(urn, assetName(urn, kinds[urn]));
    if (l.input === l.output) {
      add(fromItself, l.input);
      continue;
    }
    const key = JSON.stringify([l.input, l.output]);
    const edge = edges.get(key) ?? { from: l.input, to: l.output, pipelines: [], nodes: [] };
    add(edge.pipelines, l.pipeline);
    add(edge.nodes, l.node);
    edges.set(key, edge);
  }
  return { assets: [...assets.values()], edges: [...edges.values()], fromItself };
}

/** A box's size; LineageGraph.tsx draws boxes this size. */
export const BOX_WIDTH = 224;
export const BOX_HEIGHT = 66;
/** Room above the boxes for the column headings and the pipeline's band label (two lines). */
export const HEAD_ROOM = 60;
const MARGIN = 12;
/** How far below the lower of its two boxes a line bows when it goes back to the left. */
const BOW = 22;
/** How far below a box's middle a line back to the left leaves and arrives. */
const BACK_DROP = 14;

export type Point = { x: number; y: number };
/** One cubic piece of a line: its start, two control points and its end. */
export type Cubic = [Point, Point, Point, Point];
export type PlacedEdge = LineageEdge & {
  path: string;
  curves: Cubic[];
  label: Point | null;
  /** The line goes back to the left: the pair is part of a loop of lineage. */
  back: boolean;
};
export type LineageLayout = {
  width: number;
  height: number;
  /** Each box's top left. */
  at: Map<string, Point>;
  edges: PlacedEdge[];
  /** The band between the first and the last column, where the pipeline goes; null with one column. */
  band: { x: number; width: number } | null;
  /** The left edge of the first column and of the last, for their headings. */
  columns: { first: number; last: number } | null;
};

/** The text of an edge's label: its pipelines, the first two and how many more. */
export function edgeLabel(e: LineageEdge): string {
  if (e.pipelines.length <= 2) return e.pipelines.join(", ");
  return `${e.pipelines.slice(0, 2).join(", ")} and ${e.pipelines.length - 2} more`;
}

/** About how wide a label is drawn, for dagre to make room for it; its CSS caps it at the same. */
export const labelWidth = (text: string) => Math.min(180, 16 + text.length * 7);
const LABEL_HEIGHT = 22;

/**
 * Smooth pieces through the points, level at each one, so a line leaves the side of a box and
 * arrives at the side of the next, in whichever direction it goes.
 */
function cubics(points: Point[]): Cubic[] {
  const out: Cubic[] = [];
  for (let i = 1; i < points.length; i++) {
    const a = points[i - 1];
    const b = points[i];
    const s = b.x >= a.x ? 1 : -1;
    const dx = Math.max(16, Math.abs(b.x - a.x) / 2);
    out.push([a, { x: a.x + s * dx, y: a.y }, { x: b.x - s * dx, y: b.y }, b]);
  }
  return out;
}

const pathOf = (cs: Cubic[]) =>
  cs.length === 0
    ? ""
    : `M ${cs[0][0].x} ${cs[0][0].y}` + cs.map(([, c1, c2, b]) => ` C ${c1.x} ${c1.y}, ${c2.x} ${c2.y}, ${b.x} ${b.y}`).join("");

/** Names for the two unseen nodes that hold inputs to the first column and outputs to the last. */
const FIRST = "\u0000first";
const LAST = "\u0000last";

/**
 * Where everything goes, left to right. Every asset nothing came from (an input) is in the first
 * column and every asset nothing came from in turn (an output) in the last; assets a run both wrote
 * and read go between, each right of what it came from. Lines follow dagre's route, so a line that
 * skips a column passes between its boxes, never behind one. A line back to the left (a loop of
 * lineage) leaves the left side of its box and bows below. `labels` makes room for each edge's
 * pipelines on its line (the asset page); without, the gap between the first and the last column
 * is the pipeline's band (a run).
 */
export function layoutLineage(model: LineageModel, options: { labels?: boolean } = {}): LineageLayout {
  const labels = options.labels === true;
  const g = new Graph();
  g.setGraph({ rankdir: "LR", nodesep: 22, edgesep: 30, ranksep: labels ? 140 : 190, marginx: 0, marginy: 0 });
  g.setDefaultEdgeLabel(() => ({}));
  for (const a of model.assets) g.setNode(a.urn, { width: BOX_WIDTH, height: BOX_HEIGHT });
  for (const e of model.edges) {
    const text = labels ? edgeLabel(e) : "";
    g.setEdge(e.from, e.to, text ? { width: labelWidth(text), height: LABEL_HEIGHT, labelpos: "c" } : {});
  }
  // Tie inputs to one unseen node before them and outputs to one after, so dagre's ranking
  // (which shortens lines) cannot pull an input or an output into a middle column.
  const fed = new Set(model.edges.map((e) => e.to));
  const feeding = new Set(model.edges.map((e) => e.from));
  const inputs = model.assets.filter((a) => feeding.has(a.urn) && !fed.has(a.urn));
  // An asset recorded only as coming from itself was written: it goes with the outputs, never alone
  // in a column of its own before the inputs.
  const outputs = model.assets.filter(
    (a) => !feeding.has(a.urn) && (fed.has(a.urn) || model.fromItself.includes(a.urn)),
  );
  if (inputs.length > 1) {
    g.setNode(FIRST, { width: 0, height: 0 });
    for (const a of inputs) g.setEdge(FIRST, a.urn, { weight: 1000 });
  }
  if (outputs.length > 1) {
    g.setNode(LAST, { width: 0, height: 0 });
    for (const a of outputs) g.setEdge(a.urn, LAST, { weight: 1000 });
  }
  layout(g);

  // Everything in dagre's own coordinates first; moved into the frame below.
  const centre = (urn: string) => g.node(urn) as unknown as Point;
  // Each asset's column, to tell a line to the next column (drawn straight across the gap, through
  // its label if it has one) from a line that skips columns (drawn along dagre's route between boxes).
  const xs = [...new Set(model.assets.map((a) => centre(a.urn).x))].sort((a, b) => a - b);
  const column = (urn: string) => xs.indexOf(centre(urn).x);
  const raw = model.edges.map((e) => {
    const f = centre(e.from);
    const t = centre(e.to);
    const drawn = g.edge(e.from, e.to) as { points?: Point[]; x?: number; y?: number };
    const back = t.x <= f.x;
    // A line back leaves and arrives below the middle, apart from any line forward between the two.
    const drop = back ? BACK_DROP : 0;
    const start = { x: f.x + (back ? -1 : 1) * (BOX_WIDTH / 2), y: f.y + drop };
    const end = { x: t.x + (back ? 1 : -1) * (BOX_WIDTH / 2 + 2), y: t.y + drop };
    const label = labels && edgeLabel(e) && drawn.x != null && drawn.y != null ? { x: drawn.x, y: drawn.y } : null;
    const skips = Math.abs(column(e.to) - column(e.from)) > 1;
    let inner = skips ? (drawn.points ?? []).slice(1, -1) : label ? [label] : [];
    // Level through the whole of its label, so its bends fall outside the column of labels and never
    // behind another line's label.
    if (label) {
      const w = labelWidth(edgeLabel(e)) / 2;
      const s = back ? -1 : 1;
      inner = inner.flatMap((p) =>
        Math.abs(p.x - label.x) < 0.5 && Math.abs(p.y - label.y) < 0.5
          ? [{ x: label.x - s * w, y: label.y }, { x: label.x + s * w, y: label.y }]
          : [p],
      );
    }
    // A line back between neighbouring columns would lie on the line forward: bow it below both boxes.
    if (back && inner.length === 0) inner = [{ x: (start.x + end.x) / 2, y: Math.max(f.y, t.y) + BOX_HEIGHT / 2 + BOW }];
    return { e, back, curves: cubics([start, ...inner, end]), label };
  });

  // The drawing's extent: boxes, every point of every line (a curve stays inside its control
  // points), and labels.
  let left = Infinity;
  let top = Infinity;
  let right = -Infinity;
  let bottom = -Infinity;
  const take = (x0: number, y0: number, x1: number, y1: number) => {
    left = Math.min(left, x0);
    top = Math.min(top, y0);
    right = Math.max(right, x1);
    bottom = Math.max(bottom, y1);
  };
  for (const a of model.assets) {
    const n = centre(a.urn);
    take(n.x - BOX_WIDTH / 2, n.y - BOX_HEIGHT / 2, n.x + BOX_WIDTH / 2, n.y + BOX_HEIGHT / 2);
  }
  for (const r of raw) {
    for (const c of r.curves) for (const p of c) take(p.x, p.y, p.x, p.y);
    if (r.label) {
      const w = labelWidth(edgeLabel(r.e)) / 2;
      take(r.label.x - w, r.label.y - LABEL_HEIGHT / 2, r.label.x + w, r.label.y + LABEL_HEIGHT / 2);
    }
  }
  if (model.assets.length === 0) take(0, 0, 0, 0);
  const dx = MARGIN - left;
  const dy = HEAD_ROOM - top;
  const move = (p: Point): Point => ({ x: p.x + dx, y: p.y + dy });

  const at = new Map<string, Point>();
  for (const a of model.assets) {
    const n = centre(a.urn);
    at.set(a.urn, move({ x: n.x - BOX_WIDTH / 2, y: n.y - BOX_HEIGHT / 2 }));
  }
  const edges: PlacedEdge[] = raw.map((r) => {
    const curves = r.curves.map((c) => c.map(move) as Cubic);
    return { ...r.e, path: pathOf(curves), curves, label: r.label && move(r.label), back: r.back };
  });

  const columns = xs.length > 1 ? { first: xs[0] + dx - BOX_WIDTH / 2, last: xs[xs.length - 1] + dx - BOX_WIDTH / 2 } : null;
  const band = columns ? { x: columns.first + BOX_WIDTH + 14, width: columns.last - columns.first - BOX_WIDTH - 28 } : null;
  return {
    width: Math.ceil(right + dx + MARGIN),
    height: Math.ceil(bottom + dy + MARGIN),
    at,
    edges,
    band: band && band.width > 0 ? band : null,
    columns,
  };
}
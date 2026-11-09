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
 * file's extension, else a table. A URN that does not read so is shown whole.
 */
export function assetName(urn: string): AssetName {
  const unread: AssetName = { urn, source: "", kind: "asset", name: urn, folder: null, format: null };
  if (!urn.startsWith(PREFIX)) return unread;
  const rest = urn.slice(PREFIX.length);
  const colon = rest.indexOf(":");
  if (colon <= 0 || colon === rest.length - 1) return unread;
  const source = rest.slice(0, colon);
  const path = rest.slice(colon + 1);
  const slash = path.lastIndexOf("/");
  if (slash >= 0) {
    const file = unescapePath(path.slice(slash + 1));
    const folder = unescapePath(path.slice(0, slash));
    if (!file) return { urn, source, kind: "file", name: unescapePath(path), folder: null, format: null };
    return { urn, source, kind: "file", name: file, folder: folder || null, format: extension(file) };
  }
  const shown = unescapePath(path);
  const ext = extension(shown);
  if (ext && FILE_EXTENSIONS.has(ext)) return { urn, source, kind: "file", name: shown, folder: null, format: ext };
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

/** The graph of the recorded pairs: each pair once, an asset that came from itself set apart. */
export function lineageModel(links: LineageLink[]): LineageModel {
  const assets = new Map<string, AssetName>();
  const edges = new Map<string, LineageEdge>();
  const fromItself: string[] = [];
  const add = (list: string[], value: string | null | undefined) => {
    if (value && !list.includes(value)) list.push(value);
  };
  for (const l of links) {
    for (const urn of [l.input, l.output]) if (!assets.has(urn)) assets.set(urn, assetName(urn));
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
/** Room above the boxes for the column headings and the pipeline's band label. */
export const HEAD_ROOM = 34;
const MARGIN = 12;

export type Point = { x: number; y: number };
export type PlacedEdge = LineageEdge & { path: string; label: Point | null };
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

/** About how wide a label is drawn, for dagre to make room for it. */
const labelWidth = (text: string) => Math.min(180, 16 + text.length * 7);

/**
 * A smooth curve through the points, level at each one, so lines leave the right side of a box
 * and arrive at the left side of the next.
 */
function curve(points: Point[]): string {
  let d = `M ${points[0].x} ${points[0].y}`;
  for (let i = 1; i < points.length; i++) {
    const a = points[i - 1];
    const b = points[i];
    const dx = Math.max(24, Math.abs(b.x - a.x) / 2);
    d += ` C ${a.x + dx} ${a.y}, ${b.x - dx} ${b.y}, ${b.x} ${b.y}`;
  }
  return d;
}

/**
 * Where everything goes, left to right: an asset before every asset recorded as coming from it, so
 * inputs are on the left and outputs on the right. `labels` makes room for each edge's pipelines
 * on the line (the asset page); without, the gap between the columns is the pipeline's band (a run).
 */
export function layoutLineage(model: LineageModel, options: { labels?: boolean } = {}): LineageLayout {
  const labels = options.labels === true;
  const g = new Graph();
  g.setGraph({ rankdir: "LR", nodesep: 18, ranksep: labels ? 70 : 190, marginx: MARGIN, marginy: MARGIN });
  g.setDefaultEdgeLabel(() => ({}));
  for (const a of model.assets) g.setNode(a.urn, { width: BOX_WIDTH, height: BOX_HEIGHT });
  for (const e of model.edges) {
    const text = labels ? edgeLabel(e) : "";
    g.setEdge(e.from, e.to, text ? { width: labelWidth(text), height: 22, labelpos: "c" } : {});
  }
  layout(g);

  const at = new Map<string, Point>();
  let width = 0;
  let height = 0;
  for (const a of model.assets) {
    const n = g.node(a.urn);
    const p = { x: n.x - BOX_WIDTH / 2, y: n.y - BOX_HEIGHT / 2 + HEAD_ROOM };
    at.set(a.urn, p);
    width = Math.max(width, p.x + BOX_WIDTH + MARGIN);
    height = Math.max(height, p.y + BOX_HEIGHT + MARGIN);
  }

  const edges: PlacedEdge[] = model.edges.map((e) => {
    const from = at.get(e.from)!;
    const to = at.get(e.to)!;
    const drawn = g.edge(e.from, e.to) as { points?: Point[]; x?: number; y?: number };
    const label = labels && edgeLabel(e) && drawn.x != null && drawn.y != null ? { x: drawn.x, y: drawn.y + HEAD_ROOM } : null;
    const start = { x: from.x + BOX_WIDTH, y: from.y + BOX_HEIGHT / 2 };
    const end = { x: to.x - 2, y: to.y + BOX_HEIGHT / 2 };
    // Through the label when there is one, so it sits on its line.
    const points = label && label.x > start.x && label.x < end.x ? [start, label, end] : [start, end];
    for (const p of points) width = Math.max(width, p.x + MARGIN);
    if (label) width = Math.max(width, label.x + 90 + MARGIN);
    return { ...e, path: curve(points), label };
  });

  const xs = [...new Set([...at.values()].map((p) => p.x))].sort((a, b) => a - b);
  const columns = xs.length > 1 ? { first: xs[0], last: xs[xs.length - 1] } : null;
  const band = columns ? { x: columns.first + BOX_WIDTH + 14, width: columns.last - columns.first - BOX_WIDTH - 28 } : null;
  return { width: Math.ceil(width), height: Math.ceil(height), at, edges, band: band && band.width > 0 ? band : null, columns };
}

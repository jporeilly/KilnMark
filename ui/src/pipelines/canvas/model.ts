/**
 * The pipeline YAML as a graph, and every change the canvas makes to it. The YAML text is the one
 * document both the canvas and the YAML editor edit: the canvas reads the graph from the text, and
 * each change rewrites only the pairs and items it changes (patch.ts), splicing them into the text,
 * so every other line stays exactly as it was: comments and their alignment, indentation, quoting,
 * folded blocks and settings the canvas does not know. Nothing here touches the page; every function
 * is pure (text in, text out), which is what the unit tests check.
 *
 * Positions on the canvas live in a top-level `layout:` block, node id → { x, y }. The compiler
 * accepts and ignores it (docs/design/pipelines.md section 2), so a pipeline runs the same however
 * its nodes are drawn.
 */
import { isMap, isScalar, isSeq, LineCounter, parseDocument, Scalar, type Document, type Pair, type YAMLMap } from "yaml";
import type { NodeType } from "../../api";
import { flowYaml, isBlockMap, isBlockSeq, Patch, scalarYaml } from "./patch.ts";

export type Catalogue = ReadonlyMap<string, NodeType>;
export const catalogueOf = (types: NodeType[]): Catalogue => new Map(types.map((t) => [t.type, t]));

/** A setting as the YAML holds it. */
export type Value = string | number | boolean | null | Value[] | { [key: string]: Value };

/** One input a node names: `port` is null for `input:`, which means the node's only input port. */
export type InputLink = { port: string | null; from: string; fromPort: string };

export type GraphNode = {
  id: string;
  type: string | null;
  /** Every key but id, type, input and inputs, in the order written. */
  settings: Record<string, Value>;
  /** For scalar settings, the text as written (`007` stays `007`). */
  texts: Record<string, string>;
  inputs: InputLink[];
  /** Line of the node (from 1), and of each of its keys. */
  line: number;
  keyLines: Record<string, number>;
};

export type GraphEdge = { from: string; fromPort: string; to: string; toPort: string };
export type Point = { x: number; y: number };

export type Graph = {
  name: string | null;
  description: string | null;
  nodes: GraphNode[];
  edges: GraphEdge[];
  positions: Record<string, Point>;
};

export type ReadResult = { ok: true; graph: Graph } | { ok: false; error: string; line: number };

export const NODE_ID = /^[a-z][a-z0-9_-]{0,63}$/;
const NODE_KEYS = new Set(["id", "type", "input", "inputs"]);
export const LAYOUT = "layout";
const LAYOUT_COMMENT = " Where the canvas draws each node. Kilnmark ignores this when it runs the pipeline.";
/** Setting kinds whose text is an expression: written double-quoted, as the guides write them. */
const EXPRESSION_KINDS = new Set(["expression", "expressions", "conditions"]);

// ---- reading ----

function parse(text: string) {
  const lines = new LineCounter();
  const doc = parseDocument(text, { lineCounter: lines });
  return { doc, lines };
}

const lineOf = (lines: LineCounter, yamlNode: { range?: [number, number, number] | null } | null | undefined) =>
  yamlNode?.range ? lines.linePos(yamlNode.range[0]).line : 0;

/** `a` is a's out port; `a.rejected` another of its ports (split at the last dot, as the compiler does). */
export function parseRef(ref: string): { from: string; fromPort: string } {
  const dot = ref.lastIndexOf(".");
  return dot < 0 ? { from: ref.trim(), fromPort: "out" } : { from: ref.slice(0, dot).trim(), fromPort: ref.slice(dot + 1).trim() };
}

export const refText = (from: string, fromPort: string) => (fromPort === "out" ? from : `${from}.${fromPort}`);

function scalarText(yamlNode: unknown): string | null {
  if (!isScalar(yamlNode)) return null;
  if (yamlNode.value == null) return null;
  if (typeof yamlNode.source === "string" && yamlNode.type === Scalar.PLAIN) return yamlNode.source;
  return String(yamlNode.value);
}

function refsOf(yamlNode: unknown): string[] {
  if (isSeq(yamlNode)) return yamlNode.items.map(scalarText).filter((s): s is string => !!s && s.trim() !== "");
  const t = scalarText(yamlNode);
  return t && t.trim() !== "" ? [t] : [];
}

function linksOf(map: YAMLMap): InputLink[] {
  const out: InputLink[] = [];
  const input = map.get("input", true);
  const inputs = map.get("inputs", true);
  if (input != null) {
    for (const r of refsOf(input)) out.push({ port: null, ...parseRef(r) });
  } else if (isMap(inputs)) {
    for (const p of inputs.items) {
      const port = scalarText(p.key);
      if (!port) continue;
      for (const r of refsOf(p.value)) out.push({ port, ...parseRef(r) });
    }
  }
  return out;
}

/** The key the nodes are under: nodes, or steps in a pipeline saved before the rename (still read). */
const nodesKey = (doc: Document) => (!doc.has("nodes") && doc.has("steps") ? "steps" : "nodes");

function nodeMaps(doc: Document): YAMLMap[] {
  const nodes = doc.get(nodesKey(doc), true);
  return isSeq(nodes) ? nodes.items.filter((i): i is YAMLMap => isMap(i)) : [];
}

const idOf = (map: YAMLMap) => {
  const id = map.get("id", true);
  return isScalar(id) && typeof id.value === "string" ? id.value : null;
};

/** The node type's ports; route adds one output per route, and unknown types get the ports used. */
export function portsOf(
  node: Pick<GraphNode, "type" | "settings" | "inputs">,
  catalogue: Catalogue,
): { inputs: string[]; many: Set<string>; outputs: string[] } {
  const t = node.type ? catalogue.get(node.type) : undefined;
  if (!t) {
    const named = [...new Set(node.inputs.map((l) => l.port ?? "in"))];
    return { inputs: named.length ? named : ["in"], many: new Set(), outputs: ["out"] };
  }
  const outputs = [...t.outputs];
  if (node.type === "route") {
    const routes = node.settings.routes;
    if (routes && typeof routes === "object" && !Array.isArray(routes)) {
      const routed = Object.keys(routes).filter((k) => !outputs.includes(k));
      return { inputs: [...t.inputs], many: new Set(t.manyInputs), outputs: [...routed, ...outputs] };
    }
  }
  return { inputs: [...t.inputs], many: new Set(t.manyInputs), outputs };
}

/** The input port a link feeds. */
export const portOfLink = (link: InputLink, ports: { inputs: string[] }) => link.port ?? ports.inputs[0] ?? "in";

/**
 * Reads the graph from the YAML: nodes, settings, edges and positions. A document YAML cannot read
 * is not a graph; a document that reads but is not a valid pipeline still is (validation names its
 * problems), so the canvas can show what is there.
 */
export function readGraph(text: string, catalogue: Catalogue = new Map()): ReadResult {
  const { doc, lines } = parse(text);
  if (doc.errors.length > 0) {
    const e = doc.errors[0];
    const line = e.linePos?.[0]?.line ?? 0;
    return { ok: false, error: e.message.split("\n")[0].replace(/ at line \d+, column \d+:?$/, ""), line };
  }
  if (doc.contents != null && !isMap(doc.contents)) {
    return { ok: false, error: "A pipeline starts with kilnmark: pipeline/v1, followed by its name and nodes", line: 1 };
  }
  const nodes: GraphNode[] = [];
  const seen = new Set<string>();
  for (const map of nodeMaps(doc)) {
    const id = idOf(map);
    if (!id || seen.has(id)) continue;
    seen.add(id);
    const settings: Record<string, Value> = {};
    const texts: Record<string, string> = {};
    const keyLines: Record<string, number> = {};
    for (const pair of map.items) {
      const key = scalarText(pair.key);
      if (key == null) continue;
      keyLines[key] = lineOf(lines, pair.key as Scalar);
      if (NODE_KEYS.has(key)) continue;
      const yamlNode = pair.value;
      settings[key] = (yamlNode == null ? null : (yamlNode as { toJSON: () => Value }).toJSON()) as Value;
      if (isScalar(yamlNode)) {
        const t = scalarText(yamlNode);
        if (t != null) texts[key] = t;
      }
    }
    const typeNode = map.get("type", true);
    nodes.push({
      id,
      type: isScalar(typeNode) && typeNode.value != null ? String(typeNode.value) : null,
      settings,
      texts,
      inputs: linksOf(map),
      line: lineOf(lines, map),
      keyLines,
    });
  }
  const edges: GraphEdge[] = [];
  for (const s of nodes) {
    const ports = portsOf(s, catalogue);
    for (const l of s.inputs) edges.push({ from: l.from, fromPort: l.fromPort, to: s.id, toPort: portOfLink(l, ports) });
  }
  const positions: Record<string, Point> = {};
  const layout = doc.get(LAYOUT, true);
  if (isMap(layout)) {
    for (const p of layout.items) {
      const id = scalarText(p.key);
      const v = p.value;
      if (!id || !isMap(v)) continue;
      const x = Number((v.get("x", true) as Scalar | undefined)?.value);
      const y = Number((v.get("y", true) as Scalar | undefined)?.value);
      if (Number.isFinite(x) && Number.isFinite(y)) positions[id] = { x, y };
    }
  }
  const top = (k: string) => scalarText(doc.get(k, true));
  return { ok: true, graph: { name: top("name"), description: top("description"), nodes, edges, positions } };
}

// ---- checks ----

/** Nodes downstream of `id`, following edges. */
function downstream(graph: Graph, id: string): Set<string> {
  const out = new Set<string>();
  const queue = [id];
  while (queue.length) {
    const at = queue.shift()!;
    for (const e of graph.edges) {
      if (e.from === at && !out.has(e.to)) {
        out.add(e.to);
        queue.push(e.to);
      }
    }
  }
  return out;
}

/** Why an output port may not feed an input port, in words, or null when it may. */
export function connectProblem(graph: Graph, edge: GraphEdge, catalogue: Catalogue): string | null {
  const from = graph.nodes.find((s) => s.id === edge.from);
  const to = graph.nodes.find((s) => s.id === edge.to);
  if (!from || !to) return "Both nodes must be on the canvas.";
  if (edge.from === edge.to) return `A node cannot read from itself: ${edge.to} would feed its own input.`;
  const fromPorts = portsOf(from, catalogue);
  const toPorts = portsOf(to, catalogue);
  if (!fromPorts.outputs.includes(edge.fromPort)) return `${edge.from} has no output called ${edge.fromPort}.`;
  if (toPorts.inputs.length === 0) return `${edge.to} (${to.type}) reads from no other node: it is where rows start.`;
  if (!toPorts.inputs.includes(edge.toPort)) return `${edge.to} has no input called ${edge.toPort}.`;
  const feeding = graph.edges.filter((e) => e.to === edge.to && e.toPort === edge.toPort);
  if (feeding.some((e) => e.from === edge.from && e.fromPort === edge.fromPort)) {
    return `${refText(edge.from, edge.fromPort)} already feeds ${edge.to}.${edge.toPort}.`;
  }
  if (!toPorts.many.has(edge.toPort) && feeding.length > 0) {
    return `${edge.to}.${edge.toPort} already reads from ${refText(feeding[0].from, feeding[0].fromPort)}. Remove that connection first, or combine the rows with a union node.`;
  }
  if (downstream(graph, edge.to).has(edge.from)) {
    return `That would make a loop: ${edge.from} already reads, through other nodes, from ${edge.to}.`;
  }
  return null;
}

/** Every connection the node may make from its outputs, and to its inputs, that connectProblem allows. */
export function possibleConnections(graph: Graph, id: string, catalogue: Catalogue): GraphEdge[] {
  const me = graph.nodes.find((s) => s.id === id);
  if (!me) return [];
  const out: GraphEdge[] = [];
  const mine = portsOf(me, catalogue);
  for (const other of graph.nodes) {
    if (other.id === id) continue;
    const theirs = portsOf(other, catalogue);
    for (const fromPort of mine.outputs) {
      for (const toPort of theirs.inputs) {
        const e = { from: id, fromPort, to: other.id, toPort };
        if (!connectProblem(graph, e, catalogue)) out.push(e);
      }
    }
    for (const fromPort of theirs.outputs) {
      for (const toPort of mine.inputs) {
        const e = { from: other.id, fromPort, to: id, toPort };
        if (!connectProblem(graph, e, catalogue)) out.push(e);
      }
    }
  }
  return out;
}

// ---- changes ----

export type Change = { text: string; id?: string } | { error: string };

const CANNOT_READ = "The YAML cannot be read, so the canvas cannot change it. Fix the YAML first.";

type Ctx = { doc: Document; p: Patch; graph: Graph; catalogue: Catalogue };

/**
 * Makes a change as text edits on the original YAML, so only what changes is rewritten. The result
 * must read back as YAML; if it would not, the change is refused rather than written.
 */
/**
 * A pipeline saved before the rename lists its nodes under steps:. The canvas's first change writes
 * the key as nodes:, in place: the two are the same length, so nothing else moves.
 */
function withNodesKey(text: string): string {
  const { doc } = parse(text);
  if (doc.errors.length || !isMap(doc.contents) || doc.has("nodes")) return text;
  const key = topPair(doc, "steps")?.key as { range?: [number, number, number] } | undefined;
  const at = key?.range?.[0];
  return at != null && text.slice(at, at + 5) === "steps" ? text.slice(0, at) + "nodes" + text.slice(at + 5) : text;
}

function change(original: string, catalogue: Catalogue, fn: (ctx: Ctx) => string | void): Change {
  const text = withNodesKey(original);
  const { doc } = parse(text);
  if (doc.errors.length) return { error: CANNOT_READ };
  if (!isMap(doc.contents)) return { error: "The YAML is not a pipeline, so the canvas cannot change it." };
  const read = readGraph(text, catalogue);
  if (!read.ok) return { error: CANNOT_READ };
  const p = new Patch(text);
  try {
    const id = fn({ doc, p, graph: read.graph, catalogue });
    const out = p.result();
    if (parseDocument(out).errors.length) {
      return { error: "The canvas could not make this change without breaking the YAML. Make it in the YAML tab." };
    }
    return { text: out, id: id ?? undefined };
  } catch (e) {
    return { error: (e as Error).message };
  }
}

function findNode(doc: Document, id: string): YAMLMap | null {
  return nodeMaps(doc).find((m) => idOf(m) === id) ?? null;
}

const topPair = (doc: Document, key: string) =>
  ((doc.contents as YAMLMap).items as Pair[]).find((p) => isScalar(p.key) && String(p.key.value) === key) ?? null;

const has = (map: YAMLMap, key: string) => (map.items as Pair[]).some((p) => isScalar(p.key) && String(p.key.value) === key);

/** Writes a node's inputs: `input:` for its only port, `inputs:` by port for a node with several. */
function writeInputs(ctx: Ctx, map: YAMLMap, links: InputLink[], ports: { inputs: string[] }) {
  const id = idOf(map);
  if (has(map, "input") && has(map, "inputs")) {
    throw new Error(`Node ${id} has both input and inputs. Keep one of them in the YAML tab, then try again.`);
  }
  const { p } = ctx;
  const old = has(map, "input") ? "input" : has(map, "inputs") ? "inputs" : null;
  if (links.length === 0) {
    if (old) p.remove(map, old);
    return;
  }
  const only = ports.inputs.length <= 1 && links.every((l) => l.port == null || l.port === (ports.inputs[0] ?? "in"));
  let key: string;
  let value: unknown;
  if (only) {
    const refs = links.map((l) => refText(l.from, l.fromPort));
    key = "input";
    value = refs.length === 1 ? refs[0] : refs;
  } else {
    const byPort: Record<string, string[]> = {};
    for (const l of links) (byPort[portOfLink(l, ports)] ??= []).push(refText(l.from, l.fromPort));
    const v: Record<string, string | string[]> = {};
    for (const port of [...ports.inputs, ...Object.keys(byPort)]) {
      if (byPort[port] && !(port in v)) v[port] = byPort[port].length === 1 ? byPort[port][0] : byPort[port];
    }
    key = "inputs";
    value = v;
  }
  if (old && old !== key) p.remove(map, old);
  p.put(map, key, value, { inline: true, after: ["id", "type"] });
}

/** Writes positions in the layout block (creating it, last, if there is none) and drops `drop`. */
function writeLayout(ctx: Ctx, positions: Record<string, Point>, drop: Iterable<string> = []) {
  const { doc, p } = ctx;
  const xy = (pt: Point) => ({ x: Math.round(pt.x), y: Math.round(pt.y) });
  const pair = topPair(doc, LAYOUT);
  const layout = pair?.value;
  const dropped = new Set(drop);
  if (pair && isBlockMap(layout) && layout.items.length > 0) {
    const entries = layout.items as Pair[];
    const fresh: string[] = [];
    const indent = p.col((entries[0].key as unknown as { range: [number, number, number] }).range[0]);
    for (const [id, pt] of Object.entries(positions)) {
      if (dropped.has(id)) continue;
      const e = entries.find((x) => isScalar(x.key) && String(x.key.value) === id);
      if (e && e.value) {
        const r = (e.value as unknown as { range: [number, number, number] }).range;
        const padded = /^\{\s/.test(p.text.slice(r[0], r[1]));
        p.replace(r[0], p.valueEnd(e), flowYaml(xy(pt), false, padded));
      } else if (!e) {
        fresh.push(`${" ".repeat(indent)}${scalarYaml(id)}: ${flowYaml(xy(pt))}${p.eol}`);
      } else {
        p.replace((e.key as unknown as { range: [number, number, number] }).range[0], p.valueEnd(e), `${scalarYaml(id)}: ${flowYaml(xy(pt))}`);
      }
    }
    for (const id of dropped) p.remove(layout, id);
    if (fresh.length) {
      const end = p.pairEnd(entries[entries.length - 1]);
      p.insert(end, (p.text[end - 1] === "\n" ? "" : p.eol) + fresh.join(""));
    }
    return;
  }
  const kept: Record<string, Point> = {};
  if (pair && isMap(layout)) {
    for (const e of layout.items as Pair[]) {
      const id = scalarText(e.key);
      const v = e.value;
      if (id && isMap(v) && !dropped.has(id)) kept[id] = { x: Number(v.get("x")), y: Number(v.get("y")) };
    }
  }
  for (const [id, pt] of Object.entries(positions)) if (!dropped.has(id)) kept[id] = pt;
  const lines = Object.entries(kept).map(([id, pt]) => `  ${scalarYaml(id)}: ${flowYaml(xy(pt))}`);
  if (pair) {
    if (lines.length === 0) {
      p.replace(p.lineStart((pair.key as unknown as { range: [number, number, number] }).range[0]), p.pairEnd(pair), "");
      return;
    }
    const start = (pair.key as unknown as { range: [number, number, number] }).range[0];
    p.replace(start, p.valueEnd(pair), `${LAYOUT}:${p.eol}${lines.join(p.eol)}`);
    return;
  }
  if (lines.length === 0) return;
  const top = doc.contents as YAMLMap & { range: [number, number, number] };
  const end = top.range[1];
  const lead = end > 0 && p.text[end - 1] !== "\n" ? p.eol : "";
  p.insert(end, `${lead}${p.eol}#${LAYOUT_COMMENT}${p.eol}${LAYOUT}:${p.eol}${lines.join(p.eol)}${p.eol}`);
}

/** The text of a new node: id, type and inputs, as the nodes around it are written. */
function newNodeLines(indent: number, pairs: [string, unknown][]): string[] {
  return pairs.map(([k, v]) => `${k}: ${k === "inputs" ? flowYaml(v) : Array.isArray(v) ? flowYaml(v) : scalarYaml(v as string)}`).map((l, i) => (i === 0 ? l : " ".repeat(indent) + l));
}

/** Inserts a new node after `after` (or at the end of the nodes), written as the others are. */
function insertNode(ctx: Ctx, pairs: [string, unknown][], after: string | null) {
  const { doc, p } = ctx;
  const nodesPair = topPair(doc, nodesKey(doc));
  const seq = nodesPair?.value;
  if (isBlockSeq(seq) && seq.items.length > 0) {
    const items = seq.items as unknown as { range: [number, number, number] }[];
    const anchorIndex = after ? seq.items.findIndex((m) => isMap(m) && idOf(m) === after) : -1;
    const anchor = items[anchorIndex >= 0 ? anchorIndex : items.length - 1];
    const { prefix } = p.itemPrefix(items[0]);
    const lines = newNodeLines(prefix.length, pairs);
    const end = p.itemEnd(anchor);
    const lead = p.text[end - 1] === "\n" ? "" : p.eol;
    p.insert(end, `${lead}${p.eol}${prefix}${lines.join(p.eol)}${p.eol}`);
    return;
  }
  // No nodes yet (or written in flow style): the nodes are written out as a block list.
  const existing = isSeq(seq) ? (seq.toJSON() as Record<string, unknown>[]) : [];
  const blocks = existing.map((m) =>
    newNodeLines(4, Object.entries(m) as [string, unknown][]).join(p.eol),
  );
  blocks.push(newNodeLines(4, pairs).join(p.eol));
  const body = blocks.map((b) => `  - ${b}`).join(p.eol + p.eol);
  if (nodesPair) {
    const start = (nodesPair.key as unknown as { range: [number, number, number] }).range[0];
    p.replace(start, p.valueEnd(nodesPair), `nodes:${p.eol}${body}`);
  } else {
    const top = doc.contents as YAMLMap & { range: [number, number, number] };
    const end = top.range[1];
    p.insert(end, `${end > 0 && p.text[end - 1] !== "\n" ? p.eol : ""}nodes:${p.eol}${body}${p.eol}`);
  }
}

/** A node id from a type ("filter", "filter-2", …) not used yet. */
export function freshId(base: string, taken: Iterable<string>): string {
  const used = new Set(taken);
  let clean = base.toLowerCase().replace(/[^a-z0-9_-]+/g, "-").replace(/^[^a-z]+/, "").slice(0, 56);
  if (!clean) clean = "node";
  if (!used.has(clean)) return clean;
  for (let n = 2; ; n++) if (!used.has(`${clean}-${n}`)) return `${clean}-${n}`;
}

const nodeIds = (doc: Document) => nodeMaps(doc).map(idOf).filter((x): x is string => !!x);

/**
 * Adds a node of `type`. With `after`, the new node reads from that node's port (if it takes
 * input) and is listed after it; otherwise it is added at the end, unconnected. A position left
 * in the layout by an earlier node of the same id is replaced, or dropped.
 */
export function addNode(
  text: string,
  type: string,
  catalogue: Catalogue,
  after?: { node: string; port?: string } | null,
  position?: Point,
): Change {
  return change(text, catalogue, (ctx) => {
    const id = freshId(type, nodeIds(ctx.doc));
    const t = catalogue.get(type);
    const pairs: [string, unknown][] = [
      ["id", id],
      ["type", type],
    ];
    if (after && t && t.inputs.length > 0) {
      const ref = refText(after.node, after.port ?? "out");
      if (t.inputs.length > 1) pairs.push(["inputs", { [t.inputs[0]]: ref }]);
      else pairs.push(["input", ref]);
    }
    insertNode(ctx, pairs, after?.node ?? null);
    if (position) writeLayout(ctx, { [id]: position });
    else if (ctx.graph.positions[id]) writeLayout(ctx, {}, [id]);
    return id;
  });
}

/** A node a suggestion adds: its id, type, settings and inputs (port to `node[.port]`). */
export type NewNode = { id: string; type: string; settings: Record<string, unknown>; inputs: Record<string, string> };

/**
 * Adds a suggestion's nodes in one change (one undo step): each is listed after the node rows
 * come from, written as the others are (settings on one line each, expressions double-quoted), and
 * `before` then reads from `through` instead of `from`. Refused when the pipeline has changed so
 * the suggestion no longer fits: a node id taken since, or `before` no longer reading `from`.
 */
export function insertNodes(
  text: string,
  catalogue: Catalogue,
  nodes: NewNode[],
  placement: { before: string; port: string; from: string; through: string },
  positions: Record<string, Point> = {},
): Change {
  return change(text, catalogue, (ctx) => {
    const ids = new Set(nodeIds(ctx.doc));
    for (const s of nodes) {
      if (!NODE_ID.test(s.id) || ids.has(s.id)) throw new Error(`The pipeline already has a node called ${s.id}. Ask for the suggestions again.`);
      ids.add(s.id);
    }
    const target = ctx.graph.nodes.find((s) => s.id === placement.before);
    const map = findNode(ctx.doc, placement.before);
    if (!target || !map) throw new Error(`There is no node ${placement.before} any more. Ask for the suggestions again.`);
    const ports = portsOf(target, catalogue);
    const old = parseRef(placement.from);
    const links = target.inputs.map((l) =>
      l.from === old.from && l.fromPort === old.fromPort && portOfLink(l, ports) === placement.port ? { ...l, ...parseRef(placement.through) } : l,
    );
    if (links.every((l, i) => l === target.inputs[i])) {
      throw new Error(`${placement.before} no longer reads from ${placement.from}. Ask for the suggestions again.`);
    }
    const anchor = parseRef(placement.from).from;
    for (const s of nodes) {
      const t = catalogue.get(s.type);
      const kinds = new Map((t?.settings ?? []).map((x) => [x.key, x.kind]));
      const pairs: [string, unknown][] = [
        ["id", s.id],
        ["type", s.type],
      ];
      const inputs = Object.entries(s.inputs);
      if (inputs.length === 1 && (t?.inputs.length ?? 1) <= 1) pairs.push(["input", inputs[0][1]]);
      else if (inputs.length > 0) pairs.push(["inputs", Object.fromEntries(inputs)]);
      const lines: string[] = [];
      for (const [k, v] of Object.entries(s.settings)) {
        lines.push(`${scalarYaml(k)}: ${flowYaml(v, EXPRESSION_KINDS.has(kinds.get(k) ?? ""))}`);
      }
      insertRawNode(ctx, pairs, lines, ctx.graph.nodes.some((x) => x.id === anchor) ? anchor : null);
    }
    writeInputs(ctx, map, links, ports);
    const fresh = Object.fromEntries(Object.entries(positions).filter(([id]) => nodes.some((s) => s.id === id)));
    if (Object.keys(fresh).length) writeLayout(ctx, fresh);
    return placement.through;
  });
}

/** As insertNode, with settings lines already written. */
function insertRawNode(ctx: Ctx, pairs: [string, unknown][], settings: string[], after: string | null) {
  const { doc, p } = ctx;
  const seq = topPair(doc, nodesKey(doc))?.value;
  if (!isBlockSeq(seq) || seq.items.length === 0) {
    throw new Error("The nodes are not written as a list the canvas can add to. Add the node in the YAML tab.");
  }
  const items = seq.items as unknown as { range: [number, number, number] }[];
  const anchorIndex = after ? seq.items.findIndex((m) => isMap(m) && idOf(m) === after) : -1;
  const anchor = items[anchorIndex >= 0 ? anchorIndex : items.length - 1];
  const { prefix } = p.itemPrefix(items[0]);
  const lines = [...newNodeLines(prefix.length, pairs), ...settings.map((l) => " ".repeat(prefix.length) + l)];
  const end = p.itemEnd(anchor);
  const lead = p.text[end - 1] === "\n" ? "" : p.eol;
  p.insert(end, `${lead}${p.eol}${prefix}${lines.join(p.eol)}${p.eol}`);
}

/** Removes a node, every connection to it and its position. */
export function removeNode(text: string, id: string, catalogue: Catalogue): Change {
  return change(text, catalogue, (ctx) => {
    const seq = topPair(ctx.doc, nodesKey(ctx.doc))?.value;
    const map = findNode(ctx.doc, id);
    if (!map || !isSeq(seq)) throw new Error(`There is no node ${id}.`);
    if (!isBlockSeq(seq)) throw new Error("The nodes are written in flow style ([ … ]). Remove the node in the YAML tab.");
    for (const s of ctx.graph.nodes) {
      if (s.id === id || !s.inputs.some((l) => l.from === id)) continue;
      writeInputs(ctx, findNode(ctx.doc, s.id)!, s.inputs.filter((l) => l.from !== id), portsOf(s, catalogue));
    }
    ctx.p.removeItem(seq, map as never);
    if (ctx.graph.positions[id]) writeLayout(ctx, {}, [id]);
  });
}

/** A copy of the node, with the same settings and inputs, listed after it. */
export function duplicateNode(text: string, id: string, offset: Point = { x: 40, y: 40 }): Change {
  return change(text, new Map(), (ctx) => {
    const { p } = ctx;
    const map = findNode(ctx.doc, id) as (YAMLMap & { range: [number, number, number] }) | null;
    if (!map) throw new Error(`There is no node ${id}.`);
    const newId = freshId(`${id}-copy`, nodeIds(ctx.doc));
    const { start } = p.itemPrefix(map);
    const end = p.itemEnd(map);
    const idNode = map.get("id", true) as Scalar & { range: [number, number, number] };
    const copy = p.text.slice(start, idNode.range[0]) + scalarYaml(newId) + p.text.slice(idNode.range[1], end);
    p.insert(end, `${p.text[end - 1] === "\n" ? "" : p.eol}${p.eol}${copy}${copy.endsWith("\n") ? "" : p.eol}`);
    const at = ctx.graph.positions[id];
    if (at) writeLayout(ctx, { [newId]: { x: at.x + offset.x, y: at.y + offset.y } });
    else if (ctx.graph.positions[newId]) writeLayout(ctx, {}, [newId]);
    return newId;
  });
}

/** Gives a node a new id, and changes every input naming it and its position to match. */
export function renameNode(text: string, id: string, to: string): Change {
  if (!NODE_ID.test(to)) {
    return { error: "A node id starts with a small letter, then small letters, digits, - and _ (at most 64)." };
  }
  if (to === id) return { text };
  return change(text, new Map(), (ctx) => {
    const { doc, p } = ctx;
    if (findNode(doc, to)) throw new Error(`There is already a node called ${to}.`);
    const map = findNode(doc, id);
    if (!map) throw new Error(`There is no node ${id}.`);
    p.scalar(map.get("id", true) as Scalar, scalarYaml(to));
    const fix = (yamlNode: unknown) => {
      if (isScalar(yamlNode) && typeof yamlNode.value === "string") {
        const r = parseRef(yamlNode.value);
        if (r.from === id) p.scalar(yamlNode, scalarYaml(refText(to, r.fromPort)));
      } else if (isSeq(yamlNode)) yamlNode.items.forEach(fix);
    };
    for (const m of nodeMaps(doc)) {
      fix(m.get("input", true));
      const inputs = m.get("inputs", true);
      if (isMap(inputs)) inputs.items.forEach((e) => fix(e.value));
    }
    const layout = topPair(doc, LAYOUT)?.value;
    if (isMap(layout)) {
      const mine = (layout.items as Pair[]).find((e) => scalarText(e.key) === id);
      const stale = (layout.items as Pair[]).find((e) => scalarText(e.key) === to);
      // A position left by an earlier node called `to` would become a second key: it goes.
      if (stale) {
        if (isBlockMap(layout)) p.remove(layout, to);
        else throw new Error("The layout is written in flow style. Change it in the YAML tab.");
      }
      if (mine && isScalar(mine.key)) p.scalar(mine.key, scalarYaml(to));
    }
    return to;
  });
}

/** Connects an output port to an input port, or says why not. */
export function connect(text: string, edge: GraphEdge, catalogue: Catalogue): Change {
  return change(text, catalogue, (ctx) => {
    const problem = connectProblem(ctx.graph, edge, catalogue);
    if (problem) throw new Error(problem);
    const node = ctx.graph.nodes.find((s) => s.id === edge.to)!;
    const ports = portsOf(node, catalogue);
    const links = [...node.inputs, { port: ports.inputs.length > 1 ? edge.toPort : null, from: edge.from, fromPort: edge.fromPort }];
    writeInputs(ctx, findNode(ctx.doc, edge.to)!, links, ports);
  });
}

/** Removes the connection from an output port to an input port. */
export function disconnect(text: string, edge: GraphEdge, catalogue: Catalogue): Change {
  return change(text, catalogue, (ctx) => {
    const node = ctx.graph.nodes.find((s) => s.id === edge.to);
    if (!node) throw new Error(`There is no node ${edge.to}.`);
    const ports = portsOf(node, catalogue);
    const links = node.inputs.filter(
      (l) => !(l.from === edge.from && l.fromPort === edge.fromPort && portOfLink(l, ports) === edge.toPort),
    );
    writeInputs(ctx, findNode(ctx.doc, edge.to)!, links, ports);
  });
}

const isEmpty = (v: Value | undefined) =>
  v === undefined ||
  v === null ||
  (typeof v === "string" && v === "") ||
  (Array.isArray(v) && v.length === 0) ||
  (typeof v === "object" && v !== null && !Array.isArray(v) && Object.keys(v).length === 0);

const NUMBER_TEXT = /^-?(0|[1-9]\d*)(\.\d+)?([eE][-+]?\d+)?$/;

/**
 * Sets one setting of a node, or removes it when the value is empty. Lists are written in flow
 * style (`[a, b]`), except a gate's rules, one rule a line (`- {type: not_null, field: email}`);
 * mappings one entry a line; expressions are double-quoted, as the guides write
 * them. A text value that YAML would read as a number or true/false is quoted, so it stays text. A
 * number setting given as text is written exactly as typed, so no digits are lost.
 */
export function setSetting(text: string, id: string, key: string, value: Value | undefined, kind?: string): Change {
  if (NODE_KEYS.has(key)) return { error: `${key} is not a setting.` };
  return change(text, new Map(), (ctx) => {
    const map = findNode(ctx.doc, id);
    if (!map) throw new Error(`There is no node ${id}.`);
    if (isEmpty(value)) {
      ctx.p.remove(map, key);
      return;
    }
    if (kind === "number" && typeof value === "string" && NUMBER_TEXT.test(value.trim())) {
      const at = (map.items as Pair[]).find((e) => scalarText(e.key) === key);
      if (at?.value && isScalar(at.value)) ctx.p.scalar(at.value, value.trim());
      else ctx.p.put(map, key, Number(value), { raw: value.trim() });
      return;
    }
    ctx.p.put(map, key, value, { quote: kind != null && EXPRESSION_KINDS.has(kind), list: kind === "rules" });
  });
}

/**
 * Writes where nodes are drawn. Positions of nodes that no longer exist are dropped, so the block
 * never names a node the pipeline does not have.
 */
export function setPositions(text: string, positions: Record<string, Point>): Change {
  return change(text, new Map(), (ctx) => {
    const ids = new Set(nodeIds(ctx.doc));
    const keep = Object.fromEntries(Object.entries(positions).filter(([id]) => ids.has(id)));
    const stale = Object.keys(ctx.graph.positions).filter((id) => !ids.has(id));
    const layout = topPair(ctx.doc, LAYOUT)?.value;
    // Stale entries are dropped only when they can be: keys in a block layout.
    writeLayout(ctx, keep, isBlockMap(layout) ? stale : []);
  });
}

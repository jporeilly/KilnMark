/**
 * The pipeline YAML as a graph, and every change the canvas makes to it. The YAML text is the one
 * document both the canvas and the YAML editor edit: the canvas reads the graph from the text, and
 * each change is made to the parsed YAML document and written back, so comments, the order of keys
 * and settings the canvas does not know are kept. Nothing here touches the page; every function is
 * pure (text in, text out), which is what the unit tests check.
 *
 * Positions on the canvas live in a top-level `layout:` block, step id → { x, y }. The compiler
 * accepts and ignores it (docs/design/pipelines.md section 2), so a pipeline runs the same however
 * its steps are drawn.
 */
import {
  isMap,
  isPair,
  isScalar,
  isSeq,
  LineCounter,
  parseDocument,
  Scalar,
  YAMLMap,
  YAMLSeq,
  type Document,
  type Pair,
} from "yaml";
import type { StepType } from "../../api";

export type Catalogue = ReadonlyMap<string, StepType>;
export const catalogueOf = (types: StepType[]): Catalogue => new Map(types.map((t) => [t.type, t]));

/** A setting as the YAML holds it. */
export type Value = string | number | boolean | null | Value[] | { [key: string]: Value };

/** One input a step names: `port` is null for `input:`, which means the step's only input port. */
export type InputLink = { port: string | null; from: string; fromPort: string };

export type GraphStep = {
  id: string;
  type: string | null;
  /** Every key but id, type, input and inputs, in the order written. */
  settings: Record<string, Value>;
  /** For scalar settings, the text as written (`007` stays `007`). */
  texts: Record<string, string>;
  inputs: InputLink[];
  /** Line of the step (from 1), and of each of its keys. */
  line: number;
  keyLines: Record<string, number>;
};

export type GraphEdge = { from: string; fromPort: string; to: string; toPort: string };
export type Point = { x: number; y: number };

export type Graph = {
  name: string | null;
  description: string | null;
  steps: GraphStep[];
  edges: GraphEdge[];
  positions: Record<string, Point>;
};

export type ReadResult = { ok: true; graph: Graph } | { ok: false; error: string; line: number };

export const STEP_ID = /^[a-z][a-z0-9_-]{0,63}$/;
const STEP_KEYS = new Set(["id", "type", "input", "inputs"]);
export const LAYOUT = "layout";
const LAYOUT_COMMENT = " Where the canvas draws each step. Kilnmark ignores this when it runs the pipeline.";
/** Setting kinds whose text is an expression: written double-quoted, as the guides write them. */
const EXPRESSION_KINDS = new Set(["expression", "expressions", "conditions"]);

const TO_STRING = { lineWidth: 0, flowCollectionPadding: false } as const;

// ---- reading ----

/**
 * Numbers are written back exactly as they were typed (`007`, `2024.10`, `1e3`), never as the
 * number they read as: the server keeps the text of a setting that is text, so `asset: 2024.10`
 * must not become `2024.1`.
 */
const NUMBER_TAGS = new Set(["tag:yaml.org,2002:int", "tag:yaml.org,2002:float"]);
type Tag = { tag: string; stringify?: (node: never, ...rest: never[]) => string };
const customTags = (tags: Tag[]) =>
  tags.map((t) =>
    NUMBER_TAGS.has(t.tag) && t.stringify
      ? {
          ...t,
          stringify: (node: { source?: string }, ...rest: never[]) =>
            typeof node.source === "string" && node.source !== "" ? node.source : t.stringify!(node as never, ...rest),
        }
      : t,
  );

function parse(text: string) {
  const lines = new LineCounter();
  const doc = parseDocument(text, { lineCounter: lines, customTags: customTags as never });
  return { doc, lines };
}

const lineOf = (lines: LineCounter, node: { range?: [number, number, number] | null } | null | undefined) =>
  node?.range ? lines.linePos(node.range[0]).line : 0;

/** `a` is a's out port; `a.rejected` another of its ports. */
export function parseRef(ref: string): { from: string; fromPort: string } {
  const dot = ref.indexOf(".");
  return dot < 0 ? { from: ref.trim(), fromPort: "out" } : { from: ref.slice(0, dot).trim(), fromPort: ref.slice(dot + 1).trim() };
}

export const refText = (from: string, fromPort: string) => (fromPort === "out" ? from : `${from}.${fromPort}`);

function scalarText(node: unknown): string | null {
  if (!isScalar(node)) return null;
  if (node.value == null) return null;
  if (typeof node.source === "string" && node.type === Scalar.PLAIN) return node.source;
  return String(node.value);
}

function refsOf(node: unknown): string[] {
  if (isSeq(node)) return node.items.map(scalarText).filter((s): s is string => !!s && s.trim() !== "");
  const t = scalarText(node);
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

function stepMaps(doc: Document): YAMLMap[] {
  const steps = doc.get("steps", true);
  return isSeq(steps) ? steps.items.filter((i): i is YAMLMap => isMap(i)) : [];
}

const idOf = (map: YAMLMap) => {
  const id = map.get("id", true);
  return isScalar(id) && typeof id.value === "string" ? id.value : null;
};

/** The step type's ports; route adds one output per route, and unknown types get the ports used. */
export function portsOf(
  step: Pick<GraphStep, "type" | "settings" | "inputs">,
  catalogue: Catalogue,
): { inputs: string[]; many: Set<string>; outputs: string[] } {
  const t = step.type ? catalogue.get(step.type) : undefined;
  if (!t) {
    const named = [...new Set(step.inputs.map((l) => l.port ?? "in"))];
    return { inputs: named.length ? named : ["in"], many: new Set(), outputs: ["out"] };
  }
  const outputs = [...t.outputs];
  if (step.type === "route") {
    const routes = step.settings.routes;
    if (routes && typeof routes === "object" && !Array.isArray(routes)) {
      for (const k of Object.keys(routes)) if (!outputs.includes(k)) outputs.unshift(k);
    }
  }
  return { inputs: [...t.inputs], many: new Set(t.manyInputs), outputs };
}

/** The input port a link feeds. */
export const portOfLink = (link: InputLink, ports: { inputs: string[] }) => link.port ?? ports.inputs[0] ?? "in";

/**
 * Reads the graph from the YAML: steps, settings, edges and positions. A document YAML cannot read
 * is not a graph; a document that reads but is not a valid pipeline still is (validation names its
 * problems), so the canvas can show what is there.
 */
export function readGraph(text: string, catalogue: Catalogue = new Map()): ReadResult {
  const { doc, lines } = parse(text);
  if (doc.errors.length > 0) {
    const e = doc.errors[0];
    const line = e.linePos?.[0]?.line ?? 0;
    return { ok: false, error: e.message.split("\n")[0], line };
  }
  if (doc.contents != null && !isMap(doc.contents)) {
    return { ok: false, error: "A pipeline starts with kilnmark: pipeline/v1, followed by its name and steps", line: 1 };
  }
  const steps: GraphStep[] = [];
  const seen = new Set<string>();
  for (const map of stepMaps(doc)) {
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
      if (STEP_KEYS.has(key)) continue;
      const node = pair.value;
      settings[key] = (node == null ? null : (node as { toJSON: () => Value }).toJSON()) as Value;
      if (isScalar(node)) {
        const t = scalarText(node);
        if (t != null) texts[key] = t;
      }
    }
    const typeNode = map.get("type", true);
    steps.push({
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
  for (const s of steps) {
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
  return { ok: true, graph: { name: top("name"), description: top("description"), steps, edges, positions } };
}

// ---- checks ----

/** Steps downstream of `id`, following edges. */
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
  const from = graph.steps.find((s) => s.id === edge.from);
  const to = graph.steps.find((s) => s.id === edge.to);
  if (!from || !to) return "Both steps must be on the canvas.";
  if (edge.from === edge.to) return `A step cannot read from itself: ${edge.to} would feed its own input.`;
  const fromPorts = portsOf(from, catalogue);
  const toPorts = portsOf(to, catalogue);
  if (!fromPorts.outputs.includes(edge.fromPort)) return `${edge.from} has no output called ${edge.fromPort}.`;
  if (toPorts.inputs.length === 0) return `${edge.to} (${to.type}) reads from no other step: it is where rows start.`;
  if (!toPorts.inputs.includes(edge.toPort)) return `${edge.to} has no input called ${edge.toPort}.`;
  const feeding = graph.edges.filter((e) => e.to === edge.to && e.toPort === edge.toPort);
  if (feeding.some((e) => e.from === edge.from && e.fromPort === edge.fromPort)) {
    return `${refText(edge.from, edge.fromPort)} already feeds ${edge.to}.${edge.toPort}.`;
  }
  if (toPorts.many.has(edge.toPort)) {
    if (feeding.some((e) => e.from === edge.from)) return `${edge.from} already feeds ${edge.to}.${edge.toPort}; a step feeds a port once.`;
  } else if (feeding.length > 0) {
    return `${edge.to}.${edge.toPort} already reads from ${refText(feeding[0].from, feeding[0].fromPort)}. Remove that connection first, or combine the rows with a union step.`;
  }
  if (downstream(graph, edge.to).has(edge.from)) {
    return `That would make a loop: ${edge.from} already reads, through other steps, from ${edge.to}.`;
  }
  return null;
}

/** Every connection the step may make from its outputs, and to its inputs, that connectProblem allows. */
export function possibleConnections(graph: Graph, id: string, catalogue: Catalogue): GraphEdge[] {
  const me = graph.steps.find((s) => s.id === id);
  if (!me) return [];
  const out: GraphEdge[] = [];
  const mine = portsOf(me, catalogue);
  for (const other of graph.steps) {
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

function stepsSeq(doc: Document): YAMLSeq {
  let steps = doc.get("steps", true);
  if (!isSeq(steps)) {
    steps = new YAMLSeq();
    doc.set("steps", steps);
  }
  const seq = steps as YAMLSeq;
  seq.flow = false;
  return seq;
}

function findStep(doc: Document, id: string): YAMLMap | null {
  return stepMaps(doc).find((m) => idOf(m) === id) ?? null;
}

function keyIndex(map: YAMLMap, key: string) {
  return map.items.findIndex((p) => scalarText(p.key) === key);
}

/** Sets key to node, in place when it is there (keeping a comment on a scalar), else after `after`. */
function put(doc: Document, map: YAMLMap, key: string, node: unknown, after?: string[]) {
  const i = keyIndex(map, key);
  if (i >= 0) {
    const old = map.items[i].value;
    if (isScalar(old) && isScalar(node) && old.comment && !node.comment) node.comment = old.comment;
    map.items[i].value = node as never;
    return;
  }
  const pair = doc.createPair(key, node);
  let at = map.items.length;
  for (const a of after ?? []) {
    const j = keyIndex(map, a);
    if (j >= 0) at = j + 1;
  }
  map.items.splice(at, 0, pair as Pair<unknown, unknown>);
}

function remove(map: YAMLMap, key: string) {
  const i = keyIndex(map, key);
  if (i >= 0) map.items.splice(i, 1);
}

const serialise = (doc: Document) => doc.toString(TO_STRING);

function change(text: string, fn: (doc: Document) => string | void): Change {
  const { doc } = parse(text);
  if (doc.errors.length) return { error: "The YAML cannot be read, so the canvas cannot change it. Fix the YAML first." };
  if (doc.contents == null) doc.contents = doc.createNode({ kilnmark: "pipeline/v1" }) as never;
  if (!isMap(doc.contents)) return { error: "The YAML is not a pipeline, so the canvas cannot change it." };
  try {
    const id = fn(doc);
    return { text: serialise(doc), id: id ?? undefined };
  } catch (e) {
    return { error: (e as Error).message };
  }
}

/** Writes a step's inputs: `input:` for its only port, `inputs:` by port for a step with several. */
function writeInputs(doc: Document, map: YAMLMap, links: InputLink[], ports: { inputs: string[] }) {
  const at = Math.max(keyIndex(map, "input"), keyIndex(map, "inputs"));
  remove(map, "input");
  remove(map, "inputs");
  if (links.length === 0) return;
  const only = ports.inputs.length <= 1 && links.every((l) => l.port == null || l.port === (ports.inputs[0] ?? "in"));
  let pair;
  if (only) {
    const refs = links.map((l) => refText(l.from, l.fromPort));
    const node = refs.length === 1 ? doc.createNode(refs[0]) : doc.createNode(refs, { flow: true });
    pair = doc.createPair("input", node);
  } else {
    const byPort: Record<string, string[]> = {};
    for (const l of links) (byPort[portOfLink(l, ports)] ??= []).push(refText(l.from, l.fromPort));
    const value: Record<string, string | string[]> = {};
    for (const p of [...ports.inputs, ...Object.keys(byPort)]) {
      if (byPort[p] && !(p in value)) value[p] = byPort[p].length === 1 ? byPort[p][0] : byPort[p];
    }
    pair = doc.createPair("inputs", doc.createNode(value, { flow: true }));
  }
  let index = at;
  if (index < 0) {
    index = 0;
    for (const k of ["id", "type"]) {
      const j = keyIndex(map, k);
      if (j >= 0) index = j + 1;
    }
  }
  map.items.splice(Math.min(index, map.items.length), 0, pair as Pair<unknown, unknown>);
}

function graphOf(doc: Document, catalogue: Catalogue): Graph {
  const r = readGraph(serialise(doc), catalogue);
  if (!r.ok) throw new Error(r.error);
  return r.graph;
}

/** A step id from a type ("filter", "filter-2", …) not used yet. */
export function freshId(base: string, taken: Iterable<string>): string {
  const used = new Set(taken);
  let clean = base.toLowerCase().replace(/[^a-z0-9_-]+/g, "-").replace(/^[^a-z]+/, "").slice(0, 56);
  if (!clean) clean = "step";
  if (!used.has(clean)) return clean;
  for (let n = 2; ; n++) if (!used.has(`${clean}-${n}`)) return `${clean}-${n}`;
}

/**
 * Adds a step of `type`. With `after`, the new step reads from that step's port (if it takes
 * input) and is listed after it; otherwise it is added at the end, unconnected.
 */
export function addStep(
  text: string,
  type: string,
  catalogue: Catalogue,
  after?: { step: string; port?: string } | null,
  position?: Point,
): Change {
  return change(text, (doc) => {
    const seq = stepsSeq(doc);
    const ids = stepMaps(doc).map(idOf).filter((x): x is string => !!x);
    const id = freshId(type, ids);
    const map = doc.createNode({ id, type }) as YAMLMap;
    const t = catalogue.get(type);
    let index = seq.items.length;
    if (after) {
      const i = seq.items.findIndex((m) => isMap(m) && idOf(m) === after.step);
      if (i >= 0) index = i + 1;
      if (t && t.inputs.length > 0) {
        const port = after.port ?? "out";
        writeInputs(doc, map, [{ port: t.inputs.length > 1 ? t.inputs[0] : null, from: after.step, fromPort: port }], t);
      }
    }
    if (seq.items.length > 0) map.spaceBefore = true;
    seq.items.splice(index, 0, map);
    if (position) writePositions(doc, { [id]: position });
    return id;
  });
}

/** Removes a step, every connection to it and its position. */
export function removeStep(text: string, id: string, catalogue: Catalogue): Change {
  return change(text, (doc) => {
    const seq = stepsSeq(doc);
    const i = seq.items.findIndex((m) => isMap(m) && idOf(m) === id);
    if (i < 0) throw new Error(`There is no step ${id}.`);
    const graph = graphOf(doc, catalogue);
    seq.items.splice(i, 1);
    for (const s of graph.steps) {
      if (s.id === id || !s.inputs.some((l) => l.from === id)) continue;
      const map = findStep(doc, s.id)!;
      writeInputs(doc, map, s.inputs.filter((l) => l.from !== id), portsOf(s, catalogue));
    }
    const layout = doc.get(LAYOUT, true);
    if (isMap(layout)) remove(layout, id);
  });
}

/** A copy of the step, with the same settings and inputs, listed after it. */
export function duplicateStep(text: string, id: string, offset: Point = { x: 40, y: 40 }): Change {
  return change(text, (doc) => {
    const seq = stepsSeq(doc);
    const i = seq.items.findIndex((m) => isMap(m) && idOf(m) === id);
    if (i < 0) throw new Error(`There is no step ${id}.`);
    const copy = (seq.items[i] as YAMLMap).clone() as YAMLMap;
    const ids = stepMaps(doc).map(idOf).filter((x): x is string => !!x);
    const newId = freshId(`${id}-copy`, ids);
    put(doc, copy, "id", doc.createNode(newId));
    copy.spaceBefore = true;
    seq.items.splice(i + 1, 0, copy);
    const at = graphOf(doc, new Map()).positions[id];
    if (at) writePositions(doc, { [newId]: { x: at.x + offset.x, y: at.y + offset.y } });
    return newId;
  });
}

/** Gives a step a new id, and changes every input naming it and its position to match. */
export function renameStep(text: string, id: string, to: string): Change {
  if (!STEP_ID.test(to)) {
    return { error: "A step id starts with a small letter, then small letters, digits, - and _ (at most 64)." };
  }
  if (to === id) return { text };
  return change(text, (doc) => {
    if (findStep(doc, to)) throw new Error(`There is already a step called ${to}.`);
    const map = findStep(doc, id);
    if (!map) throw new Error(`There is no step ${id}.`);
    const idNode = map.get("id", true);
    if (isScalar(idNode)) idNode.value = to;
    const fix = (node: unknown) => {
      if (isScalar(node) && typeof node.value === "string") {
        const r = parseRef(node.value);
        if (r.from === id) node.value = refText(to, r.fromPort);
      } else if (isSeq(node)) node.items.forEach(fix);
    };
    for (const m of stepMaps(doc)) {
      fix(m.get("input", true));
      const inputs = m.get("inputs", true);
      if (isMap(inputs)) inputs.items.forEach((p) => fix(p.value));
    }
    const layout = doc.get(LAYOUT, true);
    if (isMap(layout)) {
      const p = layout.items.find((x) => scalarText(x.key) === id);
      if (p && isScalar(p.key)) p.key.value = to;
    }
    return to;
  });
}

/** Connects an output port to an input port, or says why not. */
export function connect(text: string, edge: GraphEdge, catalogue: Catalogue): Change {
  const g = readGraph(text, catalogue);
  if (!g.ok) return { error: "The YAML cannot be read, so the canvas cannot change it. Fix the YAML first." };
  const problem = connectProblem(g.graph, edge, catalogue);
  if (problem) return { error: problem };
  return change(text, (doc) => {
    const step = g.graph.steps.find((s) => s.id === edge.to)!;
    const ports = portsOf(step, catalogue);
    const links = [...step.inputs, { port: ports.inputs.length > 1 ? edge.toPort : null, from: edge.from, fromPort: edge.fromPort }];
    writeInputs(doc, findStep(doc, edge.to)!, links, ports);
  });
}

/** Removes the connection from an output port to an input port. */
export function disconnect(text: string, edge: GraphEdge, catalogue: Catalogue): Change {
  return change(text, (doc) => {
    const graph = graphOf(doc, catalogue);
    const step = graph.steps.find((s) => s.id === edge.to);
    if (!step) throw new Error(`There is no step ${edge.to}.`);
    const ports = portsOf(step, catalogue);
    const links = step.inputs.filter(
      (l) => !(l.from === edge.from && l.fromPort === edge.fromPort && portOfLink(l, ports) === edge.toPort),
    );
    writeInputs(doc, findStep(doc, edge.to)!, links, ports);
  });
}

const isEmpty = (v: Value | undefined) =>
  v === undefined ||
  v === null ||
  (typeof v === "string" && v === "") ||
  (Array.isArray(v) && v.length === 0) ||
  (typeof v === "object" && v !== null && !Array.isArray(v) && Object.keys(v).length === 0);

/**
 * Sets one setting of a step, or removes it when the value is empty. Lists are written in flow
 * style (`[a, b]`), mappings one entry a line; expressions are double-quoted, as the guides write
 * them. A text value that YAML would read as a number or true/false is quoted, so it stays text.
 */
export function setSetting(text: string, id: string, key: string, value: Value | undefined, kind?: string): Change {
  if (STEP_KEYS.has(key)) return { error: `${key} is not a setting.` };
  return change(text, (doc) => {
    const map = findStep(doc, id);
    if (!map) throw new Error(`There is no step ${id}.`);
    if (isEmpty(value)) {
      remove(map, key);
      return;
    }
    const expr = kind != null && EXPRESSION_KINDS.has(kind);
    const node = doc.createNode(value);
    if (isSeq(node)) node.flow = true;
    if (isMap(node)) {
      node.flow = false;
      if (expr) for (const p of node.items) if (isScalar(p.value) && typeof p.value.value === "string") p.value.type = Scalar.QUOTE_DOUBLE;
    }
    if (isScalar(node) && typeof node.value === "string" && expr) node.type = Scalar.QUOTE_DOUBLE;
    put(doc, map, key, node);
  });
}

function writePositions(doc: Document, positions: Record<string, Point>) {
  let layout = doc.get(LAYOUT, true);
  if (!isMap(layout)) {
    remove(doc.contents as YAMLMap, LAYOUT);
    layout = new YAMLMap();
    const key = doc.createNode(LAYOUT) as Scalar;
    key.commentBefore = LAYOUT_COMMENT;
    key.spaceBefore = true;
    (doc.contents as YAMLMap).items.push(doc.createPair(key, layout) as Pair<unknown, unknown>);
  }
  const map = layout as YAMLMap;
  for (const [id, p] of Object.entries(positions)) {
    const node = doc.createNode({ x: Math.round(p.x), y: Math.round(p.y) }, { flow: true });
    put(doc, map, id, node);
  }
}

/**
 * Writes where steps are drawn. Positions of steps that no longer exist are dropped, so the block
 * never names a step the pipeline does not have.
 */
export function setPositions(text: string, positions: Record<string, Point>): Change {
  return change(text, (doc) => {
    const ids = new Set(stepMaps(doc).map(idOf));
    writePositions(
      doc,
      Object.fromEntries(Object.entries(positions).filter(([id]) => ids.has(id))),
    );
    const layout = doc.get(LAYOUT, true) as YAMLMap;
    layout.items = layout.items.filter((p) => isPair(p) && ids.has(scalarText(p.key)));
  });
}

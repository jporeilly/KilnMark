/**
 * Every change the flow canvas makes to a flow's YAML. As on the pipeline canvas
 * (pipelines/canvas/model.ts), the YAML text is the one document the diagram and the editor both
 * change: each function here rewrites only the items and pairs it changes and splices them into the
 * text, so comments, spacing and quoting elsewhere stay exactly as written. Every function is pure,
 * text in and text out, and refuses a change it cannot make cleanly rather than guessing.
 *
 * Positions live in the top-level `layout:` block, node id → { x, y }. The server accepts and
 * ignores it, so a flow runs the same however it is drawn.
 */
import { isMap, isScalar, isSeq, parseDocument, type Document, type Pair, type YAMLMap, type YAMLSeq } from "yaml";
import type { Change } from "../pipelines/canvas/model";
import { freshId } from "../pipelines/canvas/model";
import { isBlockSeq, Patch, scalarYaml, writeLayoutBlock } from "../pipelines/canvas/patch";
import { kindOf, readFlowGraph, type FlowGraph, type Point } from "./graph";

export type { Change };

/** The node types the canvas adds, in the order it offers them. */
export const NODE_TYPES = ["run-pipeline", "notify", "wait", "sql", "stop"] as const;
export type NodeTypeName = (typeof NODE_TYPES)[number];

const LAYOUT_COMMENT = " Where the diagram draws each node. Kilnmark ignores this when it runs the flow.";
const CANNOT_READ = "The YAML cannot be read, so the diagram cannot change it. Fix the YAML first.";
/** As the server: a condition is at most 1,000 characters. */
const MOST_CONDITION = 1000;

type Ctx = { doc: Document; p: Patch; graph: FlowGraph; top: YAMLMap };
type Ranged = { range: [number, number, number] };

const pairOf = (map: YAMLMap, key: string) =>
  (map.items as Pair[]).find((p) => isScalar(p.key) && String(p.key.value) === key) ?? null;

/** The key a list is under: `nodes` and `connections`, or `steps` and `paths` in a flow written before the rename. */
const listKey = (top: YAMLMap, key: "nodes" | "connections") => {
  const old = key === "nodes" ? "steps" : "paths";
  return !pairOf(top, key) && pairOf(top, old) ? old : key;
};

const listOf = (top: YAMLMap, key: "nodes" | "connections") => {
  const v = pairOf(top, listKey(top, key))?.value;
  return isSeq(v) ? v : null;
};

const idOf = (m: unknown) => {
  if (!isMap(m)) return null;
  const id = m.get("id", true);
  return isScalar(id) && id.value != null ? String(id.value) : null;
};

const endText = (m: YAMLMap, key: string) => {
  const v = m.get(key, true);
  return isScalar(v) && v.value != null ? String(v.value) : null;
};

function change(text: string, fn: (ctx: Ctx) => string | void): Change {
  const doc = parseDocument(text);
  if (doc.errors.length) return { error: CANNOT_READ };
  if (!isMap(doc.contents)) return { error: "The YAML is not a flow, so the diagram cannot change it." };
  const read = readFlowGraph(text);
  if (!read.ok) return { error: CANNOT_READ };
  const p = new Patch(text);
  try {
    const id = fn({ doc, p, graph: read.graph, top: doc.contents });
    const out = p.result();
    if (parseDocument(out).errors.length) {
      return { error: "The diagram could not make this change without breaking the YAML. Make it in the YAML below." };
    }
    return { text: out, id: id ?? undefined };
  } catch (e) {
    return { error: (e as Error).message };
  }
}

/** Lines for a new block item at `prefix` (`  - `): the first after the dash, the rest under it. */
const itemLines = (prefix: string, eol: string, pairs: [string, string][]) =>
  pairs.map(([k, v], i) => `${i === 0 ? prefix : " ".repeat(prefix.length)}${k}: ${v}`).join(eol);

/**
 * Adds a list item after the list's last item, written as the items above it are: a flow mapping
 * (`- { from: a, to: b }`) when they are, else a block of pairs. With no list yet (or an empty one),
 * the list is written after `after`'s pair, or at the end.
 */
function appendItem(ctx: Ctx, key: "nodes" | "connections", pairs: [string, string][], blankLine: boolean) {
  const { p, top } = ctx;
  const name = listKey(top, key);
  const pair = pairOf(top, name);
  const seq = pair?.value;
  const flowItem = (padded: boolean) => {
    const body = pairs.map(([k, v]) => `${k}: ${v}`).join(", ");
    return padded ? `{ ${body} }` : `{${body}}`;
  };
  if (isSeq(seq) && seq.items.length > 0) {
    if (!isBlockSeq(seq)) {
      throw new Error(`The ${name} are written in flow style ([ … ]). Make this change in the YAML below.`);
    }
    const items = seq.items as unknown as Ranged[];
    const last = items[items.length - 1];
    const { prefix } = p.itemPrefix(last);
    const lastFlow = isMap(last) && (last as unknown as YAMLMap).flow;
    const padded = lastFlow && /^\{\s/.test(p.text.slice(last.range[0], last.range[1]));
    const body = lastFlow ? `${prefix}${flowItem(!!padded)}` : itemLines(prefix, p.eol, pairs);
    const end = p.itemEnd(last);
    const lead = p.text[end - 1] === "\n" ? "" : p.eol;
    p.insert(end, `${lead}${blankLine ? p.eol : ""}${body}${p.eol}`);
    return;
  }
  const item = key === "connections" ? `  - ${flowItem(true)}` : itemLines("  - ", p.eol, pairs);
  if (pair) {
    // `connections:` with nothing (or `[]`) after it: the list is written in its place.
    const start = (pair.key as unknown as Ranged).range[0];
    p.replace(start, p.valueEnd(pair), `${name}:${p.eol}${item}`);
    return;
  }
  // No list yet: after the nodes for connections, else at the end of the flow.
  const nodes = pairOf(top, listKey(top, "nodes"));
  const at = key === "connections" && nodes ? p.pairEnd(nodes) : (top as unknown as Ranged).range[1];
  const lead = at > 0 && p.text[at - 1] !== "\n" ? p.eol : "";
  p.insert(at, `${lead}${p.eol}${name}:${p.eol}${item}${p.eol}`);
}

/** Removes list items; a list left with none is written `[]`, so the flow still reads as one. */
function removeItems(ctx: Ctx, key: "nodes" | "connections", seq: YAMLSeq, items: unknown[]) {
  const { p, top } = ctx;
  if (items.length === 0) return;
  if (!isBlockSeq(seq)) {
    throw new Error(`The ${listKey(top, key)} are written in flow style ([ … ]). Make this change in the YAML below.`);
  }
  if (items.length === seq.items.length) {
    const pair = pairOf(top, listKey(top, key))!;
    p.replace((pair.key as unknown as Ranged).range[0], p.valueEnd(pair), `${listKey(top, key)}: []`);
    return;
  }
  for (const item of items) p.removeItem(seq, item as Ranged);
}

/** The settings a new node starts with: enough to say what it does; the checks below the YAML say what is missing. */
function startingSettings(type: NodeTypeName, pipeline: string | undefined): [string, string][] {
  switch (type) {
    case "run-pipeline":
      return pipeline ? [["pipeline", scalarYaml(pipeline)]] : [];
    case "notify":
      return [["title", scalarYaml("A notice from the flow")]];
    case "wait":
      return [["for", "10m"]];
    case "stop":
      return [["status", "failed"]];
    case "sql":
      return [];
  }
}

/**
 * Adds a node of `type` (with `pipeline` for one that runs a pipeline), listed after the others,
 * with an id not yet used. Where the diagram is placed by hand, it goes at `at`.
 */
export function addNode(text: string, type: NodeTypeName, opts: { pipeline?: string; at?: Point } = {}): Change {
  return change(text, (ctx) => {
    const id = freshId(type === "run-pipeline" && opts.pipeline ? opts.pipeline : type, ctx.graph.nodes.map((n) => n.id));
    appendItem(ctx, "nodes", [["id", scalarYaml(id)], ["type", type], ...startingSettings(type, opts.pipeline)], true);
    if (opts.at && Object.keys(ctx.graph.positions).length > 0) {
      writeLayoutBlock(ctx.doc, ctx.p, { [id]: opts.at }, [], LAYOUT_COMMENT);
    }
    return id;
  });
}

/** Removes a node, every connection to or from it, and its position. */
export function removeNode(text: string, id: string): Change {
  return change(text, (ctx) => {
    const nodes = listOf(ctx.top, "nodes");
    const item = nodes?.items.find((m) => idOf(m) === id);
    if (!nodes || !item) throw new Error(`There is no node ${id}.`);
    if (nodes.items.length === 1) throw new Error("A flow needs at least one node, so the last one stays.");
    removeItems(ctx, "nodes", nodes, [item]);
    const conns = listOf(ctx.top, "connections");
    if (conns) {
      const touching = conns.items.filter((m) => isMap(m) && (endText(m, "from") === id || endText(m, "to") === id));
      removeItems(ctx, "connections", conns, touching);
    }
    if (ctx.graph.positions[id]) writeLayoutBlock(ctx.doc, ctx.p, {}, [id], LAYOUT_COMMENT);
  });
}

/** `when` as the diagram reads it: trimmed, and on success when blank. */
const norm = (when: string) => when.trim() || "success";

/** How a connection is taken, as written in `when:`: a word, or a condition in double quotes. */
function whenText(when: string): string | null {
  const w = norm(when);
  const kind = kindOf(w);
  if (kind === "success") return null;
  return kind === "condition" ? scalarYaml(w, true) : kind;
}

/** Why a condition cannot be written, or null. The server checks what it says when the flow is checked. */
function conditionProblem(when: string): string | null {
  const w = when.trim();
  if (w === "") return "Write the condition, for example clean.score >= good.";
  if (/[\r\n]/.test(w)) return "A condition is one line.";
  if (w.length > MOST_CONDITION) return `A condition is at most ${MOST_CONDITION} characters.`;
  return null;
}

/** The connections' key for one connection, as the server tells two apart. */
const sameAs = (when: string) => {
  const w = norm(when);
  const kind = kindOf(w);
  return kind === "condition" ? w : kind;
};

/** Whether `to` reaches `from` along the connections: a new connection from → to would close a loop. */
function reaches(graph: FlowGraph, start: string, goal: string): boolean {
  const seen = new Set<string>();
  const todo = [start];
  while (todo.length) {
    const n = todo.pop()!;
    if (n === goal) return true;
    if (seen.has(n)) continue;
    seen.add(n);
    for (const c of graph.connections) if (c.from === n) todo.push(c.to);
  }
  return false;
}

/** Why a connection from → to taken `when` cannot be added, or null. */
export function connectProblem(graph: FlowGraph, from: string, to: string, when: string, ignore?: number): string | null {
  const ids = new Set(graph.nodes.map((n) => n.id));
  if (!ids.has(from) || !ids.has(to)) return "Choose two nodes of the flow.";
  if (from === to) return "A connection joins two different nodes.";
  if (kindOf(norm(when)) === "condition") {
    const problem = conditionProblem(when);
    if (problem) return problem;
  }
  const key = sameAs(when);
  if (graph.connections.some((c) => c.index !== ignore && c.from === from && c.to === to && sameAs(c.when) === key)) {
    return `${from} is already connected to ${to} that way.`;
  }
  if (reaches(graph, to, from)) return `${to} already leads to ${from}, so this connection would make a loop.`;
  return null;
}

/** Connects two nodes: `to` runs after `from`, taken `when` (success, failure, always or a condition). */
export function connect(text: string, from: string, to: string, when = "success"): Change {
  return change(text, (ctx) => {
    const problem = connectProblem(ctx.graph, from, to, when);
    if (problem) throw new Error(problem);
    const w = whenText(when);
    appendItem(ctx, "connections", [["from", scalarYaml(from)], ["to", scalarYaml(to)], ...(w ? [["when", w] as [string, string]] : [])], false);
  });
}

const connectionAt = (ctx: Ctx, index: number) => {
  const conns = listOf(ctx.top, "connections");
  const item = conns?.items[index];
  if (!conns || !isMap(item)) throw new Error("That connection is no longer in the YAML.");
  return { conns, item };
};

/** Changes when a connection is taken; on success is the default, so `when:` is then removed. */
export function setWhen(text: string, index: number, when: string): Change {
  return change(text, (ctx) => {
    const { item } = connectionAt(ctx, index);
    const c = ctx.graph.connections.find((x) => x.index === index);
    if (!c) throw new Error("That connection is no longer in the YAML.");
    const problem = connectProblem(ctx.graph, c.from, c.to, when, index);
    if (problem) throw new Error(problem);
    const w = whenText(when);
    if (w == null) ctx.p.remove(item, "when");
    else ctx.p.put(item, "when", null, { raw: w });
  });
}

/** Removes a connection. */
export function disconnect(text: string, index: number): Change {
  return change(text, (ctx) => {
    const { conns, item } = connectionAt(ctx, index);
    removeItems(ctx, "connections", conns, [item]);
  });
}

/** Writes where nodes are drawn. Positions of nodes the flow no longer has are dropped. */
export function setPositions(text: string, positions: Record<string, Point>): Change {
  return change(text, (ctx) => {
    const ids = new Set(ctx.graph.nodes.map((n) => n.id));
    const keep = Object.fromEntries(Object.entries(positions).filter(([id]) => ids.has(id)));
    const layout = pairOf(ctx.top, "layout")?.value;
    const stale =
      isMap(layout) && !layout.flow
        ? (layout.items as Pair[]).map((e) => (isScalar(e.key) ? String(e.key.value) : "")).filter((id) => id && !ids.has(id))
        : [];
    writeLayoutBlock(ctx.doc, ctx.p, keep, stale, LAYOUT_COMMENT);
  });
}

/** Removes the `layout:` block (and the comment the canvas wrote above it): the diagram lays the flow out itself again. */
export function clearLayout(text: string): Change {
  return change(text, (ctx) => {
    const pair = pairOf(ctx.top, "layout");
    if (!pair) return;
    const { p } = ctx;
    let start = p.lineStart((pair.key as unknown as Ranged).range[0]);
    const above = start > 0 ? p.lineStart(start - 1) : start;
    if (start > 0 && p.text.slice(above, start).trim() === `#${LAYOUT_COMMENT}`.trim()) {
      start = above;
      const blank = start > 0 ? p.lineStart(start - 1) : start;
      if (start > 0 && p.text.slice(blank, start).trim() === "") start = blank;
    }
    p.replace(start, p.pairEnd(pair), "");
  });
}

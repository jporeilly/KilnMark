import { isMap, isScalar, isSeq, parseDocument, type Scalar } from "yaml";

/** A node type in words, as the diagram and the run page say it. */
export const TYPE_WORDS: Record<string, string> = {
  "run-pipeline": "Runs a pipeline",
  notify: "Sends a notice",
  wait: "Waits",
  stop: "Ends the flow",
  sql: "Runs SQL",
};

/** A flow node as the diagram draws it: its id, type and a few words of what it does. */
export type FlowGraphNode = { id: string; type: string; summary: string };
/** How a connection is taken: on success (the default), on failure, always, or when a condition holds. */
export type ConnectionKind = "success" | "failure" | "always" | "condition";
export type FlowConnection = { from: string; to: string; kind: ConnectionKind; when: string };
export type Point = { x: number; y: number };
export type FlowGraph = {
  nodes: FlowGraphNode[];
  connections: FlowConnection[];
  /** Where the `layout:` block puts each node, for those it names. */
  positions: Record<string, Point>;
};
export type FlowGraphResult = { ok: true; graph: FlowGraph } | { ok: false; error: string };

const text = (v: unknown): string | null => {
  if (isScalar(v)) v = (v as Scalar).value;
  if (v == null) return null;
  return typeof v === "string" || typeof v === "number" || typeof v === "boolean" ? String(v) : null;
};

/** What a node does, in a few words: its pipeline, notice, wait, source or how it ends the flow. */
function summaryOf(type: string, node: { get: (k: string) => unknown }): string {
  const get = (k: string) => text(node.get(k));
  switch (type) {
    case "run-pipeline": {
      const pipeline = get("pipeline") ?? "a pipeline";
      const version = get("version");
      return version ? `${pipeline}, version ${version}` : pipeline;
    }
    case "notify":
      return get("title") ?? "Sends a notice";
    case "wait":
      return get("for") ? `Waits ${get("for")}` : "Waits";
    case "stop":
      return get("status") === "succeeded" ? "Ends the flow: succeeded" : "Ends the flow: failed";
    case "sql": {
      const statements = node.get("statements");
      const n = isSeq(statements) ? statements.items.length : 0;
      const on = get("source") ?? "a source";
      return `${n} SQL ${n === 1 ? "statement" : "statements"} on ${on}`;
    }
    default:
      return type;
  }
}

const kindOf = (when: string): ConnectionKind =>
  when === "success" || when === "failure" || when === "always" ? when : "condition";

/**
 * Reads a flow's YAML into what the diagram draws: nodes, connections and positions. Only what is
 * needed to draw it is read; the server's check is what says whether the flow is valid. Flows written
 * before the rename use `steps:` and `paths:`, which are read too.
 */
export function readFlowGraph(yamlText: string): FlowGraphResult {
  const doc = parseDocument(yamlText);
  if (doc.errors.length > 0) return { ok: false, error: "The YAML cannot be read." };
  const top = doc.contents;
  if (!isMap(top)) return { ok: false, error: "The YAML is not a flow." };
  const nodeList = top.get("nodes", true) ?? top.get("steps", true);
  if (!isSeq(nodeList)) return { ok: false, error: "The flow has no nodes yet." };

  const nodes: FlowGraphNode[] = [];
  const ids = new Set<string>();
  for (const item of nodeList.items) {
    if (!isMap(item)) continue;
    const id = text(item.get("id", true));
    if (!id || ids.has(id)) continue;
    const type = text(item.get("type", true)) ?? "unknown";
    ids.add(id);
    nodes.push({ id, type, summary: summaryOf(type, { get: (k) => item.get(k, true) }) });
  }

  const connections: FlowConnection[] = [];
  const connList = top.get("connections", true) ?? top.get("paths", true);
  if (isSeq(connList)) {
    for (const item of connList.items) {
      if (!isMap(item)) continue;
      const from = text(item.get("from", true));
      const to = text(item.get("to", true));
      if (!from || !to || !ids.has(from) || !ids.has(to)) continue;
      const when = (text(item.get("when", true)) ?? "success").trim() || "success";
      connections.push({ from, to, kind: kindOf(when), when });
    }
  }

  const positions: Record<string, Point> = {};
  const layout = top.get("layout", true);
  if (isMap(layout)) {
    for (const p of layout.items) {
      const id = text(p.key);
      if (!id || !ids.has(id) || !isMap(p.value)) continue;
      const x = Number(text(p.value.get("x", true)));
      const y = Number(text(p.value.get("y", true)));
      if (Number.isFinite(x) && Number.isFinite(y)) positions[id] = { x, y };
    }
  }
  return { ok: true, graph: { nodes, connections, positions } };
}

/** A connection's label: when it is taken, in words; a long condition is shortened (the full text is in its title). */
export function whenWords(c: FlowConnection, most = 40): string {
  switch (c.kind) {
    case "success":
      return "on success";
    case "failure":
      return "on failure";
    case "always":
      return "always";
    default:
      return c.when.length > most ? `${c.when.slice(0, most - 1).trimEnd()}…` : c.when;
  }
}

/** A connection in a sentence, for screen readers and the title. */
export function connectionSentence(c: FlowConnection): string {
  switch (c.kind) {
    case "success":
      return `${c.to} runs when ${c.from} succeeds`;
    case "failure":
      return `${c.to} runs when ${c.from} fails`;
    case "always":
      return `${c.to} runs when ${c.from} ends, whether it succeeds or fails`;
    default:
      return `${c.to} runs when ${c.from} succeeds and ${c.when}`;
  }
}

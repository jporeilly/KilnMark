/**
 * What the canvas says about nodes, worked out from the graph and the latest validation: a
 * one-line summary of each node's settings, the columns arriving at a node, and which node and
 * setting each problem belongs to. Pure functions, unit tested.
 */
import type { PipelineColumn, PipelineProblem, PipelineValidation, NodeType, NodeTypeSetting } from "../../api";
import type { GraphNode, Value } from "./model";

export const KINDS = [
  { kind: "source", label: "Sources" },
  { kind: "transform", label: "Transforms" },
  { kind: "gate", label: "Gates" },
  { kind: "sink", label: "Sinks" },
] as const;

export const kindWord = (kind: string | null | undefined) =>
  kind === "source" ? "Source" : kind === "sink" ? "Sink" : kind === "gate" ? "Gate" : kind === "transform" ? "Transform" : "Node";

/** A target that names a file rather than a table: it has a folder or a file extension. */
const FILE_LIKE = /[/\\]|\.(csv|tsv|txt|json|jsonl|ndjson|parquet|avro|orc|xlsx?|xml)$/i;

/**
 * The words after the kind in a node's header. A write says what it writes to, from its own
 * settings, rather than the type's "Write to a table or file" (which a node's width cuts to "Write
 * to a table…" even for a file): "Write to a file" with `path` or a file-like `target`, "Write to a
 * table" with `table` or another `target`. Other nodes keep their type's label.
 */
export function nodeLabel(node: Pick<GraphNode, "type" | "settings">, label: string): string {
  if (node.type !== "write") return label;
  const s = node.settings;
  if (s.path != null) return "Write to a file";
  if (s.table != null) return "Write to a table";
  if (typeof s.target === "string" && s.target !== "") return FILE_LIKE.test(s.target) ? "Write to a file" : "Write to a table";
  return label;
}

const text = (v: Value | undefined): string => {
  if (v == null) return "";
  if (Array.isArray(v)) return v.map(text).join(", ");
  if (typeof v === "object") return Object.keys(v).join(", ");
  return String(v);
};

const keysOf = (v: Value | undefined) => (v && typeof v === "object" && !Array.isArray(v) ? Object.keys(v) : []);

/** One line saying what the node does with its settings; empty when it has none yet. */
export function nodeSummary(node: GraphNode): string {
  const s = node.settings;
  const t = (k: string) => node.texts[k] ?? text(s[k]);
  switch (node.type) {
    case "read":
      return [t("source"), t("asset")].filter(Boolean).join(" · ");
    case "write": {
      const where = t("target") || t("table") || t("path");
      return [t("source"), where, s.mode != null ? t("mode") : ""].filter(Boolean).join(" · ");
    }
    case "filter":
      return t("condition");
    case "compute":
      return keysOf(s.columns).join(", ");
    case "aggregate": {
      const by = text(s.group_by);
      const values = keysOf(s.values).join(", ");
      return [by ? `by ${by}` : "", values].filter(Boolean).join(": ");
    }
    case "lookup": {
      const on = s.on && typeof s.on === "object" && !Array.isArray(s.on) ? Object.entries(s.on).map(([a, b]) => `${a} = ${text(b)}`) : [];
      return on.length ? `on ${on.join(", ")}` : "";
    }
    case "select": {
      const parts = [];
      if (s.keep != null) parts.push(`keep ${text(s.keep)}`);
      if (s.drop != null) parts.push(`drop ${text(s.drop)}`);
      if (s.rename != null) parts.push(`rename ${keysOf(s.rename).join(", ")}`);
      if (s.cast != null) parts.push(`cast ${keysOf(s.cast).join(", ")}`);
      return parts.join("; ");
    }
    case "union":
      return `${node.inputs.length} ${node.inputs.length === 1 ? "input" : "inputs"}`;
    case "dedupe":
      return s.key != null ? `key ${text(s.key)}` : "whole rows";
    case "route":
      return keysOf(s.routes).join(", ");
    case "limit":
      return s.rows != null ? `first ${t("rows")} rows` : "";
    case "gate": {
      const n = Array.isArray(s.rules) ? s.rules.length : 0;
      const own = s.asset_rules === false || s.asset_rules === "false" ? "" : "asset's rules";
      const written = n ? `${n} ${n === 1 ? "rule" : "rules"}` : "";
      const checks = [own, written].filter(Boolean).join(" + ");
      return [s.on_fail != null ? t("on_fail") : "stop", checks].filter(Boolean).join(" · ");
    }
    default:
      return Object.keys(s)
        .slice(0, 2)
        .map((k) => `${k}: ${t(k)}`)
        .join(", ");
  }
}

/**
 * The columns arriving at a node's input port (its first when none is named), from the latest
 * validation: null when they are not known before a run (or not checked yet).
 */
export function arrivingColumns(check: PipelineValidation | null, nodeId: string, port?: string): PipelineColumn[] | null {
  if (!check) return null;
  const outline = check.nodes.find((s) => s.id === nodeId);
  const want = port ?? outline?.inputs[0];
  const feeding = check.edges.filter((e) => e.toNode === nodeId && (want == null || e.toPort === want));
  if (feeding.length === 0) return null;
  const out: PipelineColumn[] = [];
  for (const e of feeding) {
    const cols = check.nodes.find((s) => s.id === e.fromNode)?.columns[e.fromPort];
    if (cols == null) return null;
    for (const c of cols) if (!out.some((o) => o.name === c.name)) out.push(c);
  }
  return out;
}

/** Which input's columns a setting names: the lookup side for a lookup's keep, else the first input. */
export function columnSide(nodeType: string | null, key: string): "main" | "lookup" | null {
  if (nodeType === "lookup") return key === "keep" ? "lookup" : "main";
  return null;
}

/**
 * The setting a problem is about, from its words or its line; null when it is about the node.
 * Problems saved before nodes were called nodes (V21) say "Step", so both words are read.
 */
export function problemSetting(p: PipelineProblem, node: GraphNode): string | null {
  const keys = Object.keys(node.settings);
  const named =
    /Setting '([^']+)' of (?:node|step)/.exec(p.message)?.[1] ??
    /has no setting '([^']+)'/.exec(p.message)?.[1] ??
    /needs the setting '([^']+)'/.exec(p.message)?.[1];
  if (named) return named;
  const lead = /^(?:Node|Step) [^:]+: ([a-z_][a-z0-9_]*):/.exec(p.message)?.[1];
  if (lead && (keys.includes(lead) || /^[a-z_]+$/.test(lead))) return lead;
  if (p.line > 0) {
    for (const [k, line] of Object.entries(node.keyLines)) if (line === p.line && !["id", "type", "input", "inputs"].includes(k)) return k;
  }
  return null;
}

/** Problems by node id. */
export function problemsByNode(problems: PipelineProblem[]): Map<string, PipelineProblem[]> {
  const out = new Map<string, PipelineProblem[]>();
  for (const p of problems) if (p.node) out.set(p.node, [...(out.get(p.node) ?? []), p]);
  return out;
}

/** A problem's words without the "Node x: " the list already says. */
export const problemWords = (p: PipelineProblem) => p.message.replace(/^(?:Node|Step) [a-z][a-z0-9_-]*: /, "");

const KEY_OF_TARGET = new Set(["table", "path"]);

/**
 * The settings the panel shows for a node: the type's own, less the writer settings a write's
 * source does not take (those its source's kind declares are shown once a source is chosen), less
 * `table` and `path` (other names for `target`) unless the YAML uses them. A setting the YAML has
 * that the type does not list is still shown, so it can be seen and removed.
 */
export function shownSettings(
  type: NodeType | undefined,
  node: GraphNode,
  sourceKind: string | null,
): { specs: NodeTypeSetting[]; writerHidden: number } {
  if (!type) return { specs: [], writerHidden: 0 };
  const writerKeys = new Set(Object.values(type.writerSettings ?? {}).flat());
  const allowed = sourceKind ? new Set(type.writerSettings?.[sourceKind] ?? []) : new Set<string>();
  let writerHidden = 0;
  const specs = type.settings.filter((spec) => {
    const set = spec.key in node.settings;
    if (type.type === "write" && KEY_OF_TARGET.has(spec.key)) return set;
    if (writerKeys.has(spec.key) && !allowed.has(spec.key) && !set) {
      writerHidden++;
      return false;
    }
    return true;
  });
  return { specs, writerHidden };
}

/** The default a setting takes when left out, in words, or null. */
export function defaultText(spec: NodeTypeSetting): string | null {
  const d = spec.defaultValue;
  if (d == null || d === "") return null;
  if (typeof d === "boolean") return d ? "on" : "off";
  return String(d);
}

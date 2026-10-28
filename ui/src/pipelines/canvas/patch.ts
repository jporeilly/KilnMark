/**
 * Changes to YAML text that touch only what changes. The canvas parses the text (keeping each
 * node's offsets), works out which pairs and items change, writes just those with the `yaml`
 * library, and splices them into the original text. Every other byte stays as it was: comments
 * and their alignment, flow maps' spacing, indentation, folded and literal blocks, quoting and line
 * endings.
 */
import { Document, isCollection, isMap, isScalar, isSeq, Scalar, type Pair, type YAMLMap, type YAMLSeq } from "yaml";

type Range = [number, number, number];
type Ranged = { range?: Range | null };
export type Edit = { start: number; end: number; text: string };

const TO_STRING = { lineWidth: 0, flowCollectionPadding: false } as const;

/** Writes one scalar as YAML would read it back: quoted when plain text would read as something else. */
export function scalarYaml(value: string | number | boolean | null, quote = false): string {
  const d = new Document(value);
  const node = d.contents as Scalar;
  if (typeof value === "string" && (quote || value.includes("\n"))) node.type = Scalar.QUOTE_DOUBLE;
  return d.toString(TO_STRING).replace(/\n$/, "");
}

/** A value on one line: a scalar, or a flow list or mapping (`[a, b]`, `{x: 1, y: 2}`). */
export function flowYaml(value: unknown, quoteStrings = false, padded = false): string {
  const d = new Document(value);
  const style = (n: unknown) => {
    if (isScalar(n) && typeof n.value === "string" && (quoteStrings || n.value.includes("\n"))) n.type = Scalar.QUOTE_DOUBLE;
    if (isCollection(n)) {
      n.flow = true;
      for (const it of n.items as unknown[]) {
        if (isScalar(it) || isCollection(it)) style(it);
        else if (it && typeof it === "object" && "value" in it) style((it as Pair).value);
      }
    }
  };
  style(d.contents);
  return d.toString({ ...TO_STRING, flowCollectionPadding: padded }).replace(/\n$/, "");
}

export class Patch {
  readonly edits: (Edit & { n: number })[] = [];
  readonly eol: string;
  readonly text: string;

  constructor(text: string) {
    this.text = text;
    this.eol = text.includes("\r\n") ? "\r\n" : "\n";
  }

  replace(start: number, end: number, text: string) {
    this.edits.push({ start, end, text, n: this.edits.length });
  }

  insert(at: number, text: string) {
    this.replace(at, at, text);
  }

  lineStart(i: number) {
    return this.text.lastIndexOf("\n", i - 1) + 1;
  }

  /** The index just after the line break ending the line that holds `i` (or the end of the text). */
  lineEnd(i: number) {
    const n = this.text.indexOf("\n", i);
    return n < 0 ? this.text.length : n + 1;
  }

  col(i: number) {
    return i - this.lineStart(i);
  }

  /** Where a block pair ends: after its value, its comment and its line break. */
  pairEnd(p: Pair): number {
    const v = p.value as Ranged | null;
    const k = p.key as Ranged;
    const e = v?.range ? v.range[2] : k.range![1];
    return this.text[e - 1] === "\n" ? e : this.lineEnd(Math.max(e - 1, 0));
  }

  /** Where a pair's value ends, before any comment and line break after it. */
  valueEnd(p: Pair): number {
    const v = p.value as Ranged | null;
    if (!v?.range) return this.text.indexOf(":", (p.key as Ranged).range![1]) + 1;
    let e = v.range[1];
    while (e > v.range[0] && /[\r\n]/.test(this.text[e - 1])) e--;
    return e;
  }

  /** The text of a pair at `indent`: `key: value`, with a block mapping on the lines below. */
  pairText(
    key: string,
    value: unknown,
    indent: number,
    opts: { flow: boolean; quote: boolean; inline?: boolean; raw?: string; list?: boolean },
  ): string {
    const k = scalarYaml(key);
    if (opts.raw != null) return `${k}: ${opts.raw}`;
    if (opts.list && Array.isArray(value) && value.length > 0 && !opts.flow) {
      // A block list, one item a line: `- {type: not_null, field: email}`.
      const pad = " ".repeat(indent + 2);
      return `${k}:${this.eol}${value.map((item) => `${pad}- ${flowYaml(item, opts.quote)}`).join(this.eol)}`;
    }
    if (value && typeof value === "object" && !Array.isArray(value) && !opts.flow && !opts.inline) {
      const pad = " ".repeat(indent + 2);
      const lines = Object.entries(value as Record<string, unknown>).map(
        ([ek, ev]) => `${pad}${scalarYaml(ek)}: ${flowYaml(ev, opts.quote)}`,
      );
      return `${k}:${this.eol}${lines.join(this.eol)}`;
    }
    return `${k}: ${flowYaml(value, opts.quote)}`;
  }

  /** Sets a key of a mapping: its value in place when it is there, else a new pair after `after`. */
  put(
    map: YAMLMap,
    key: string,
    value: unknown,
    opts: { quote?: boolean; after?: string[]; inline?: boolean; raw?: string; list?: boolean } = {},
  ) {
    const flow = !!map.flow;
    const pairs = map.items as Pair[];
    const at = pairs.find((p) => isScalar(p.key) && String(p.key.value) === key);
    const first = (pairs[0]?.key as Ranged | undefined)?.range?.[0];
    const indent = first != null ? this.col(first) : 2;
    const text = this.pairText(key, value, indent, { flow, quote: !!opts.quote, inline: opts.inline, raw: opts.raw, list: opts.list });
    if (at) {
      this.replace((at.key as Ranged).range![0], this.valueEnd(at), text);
      return;
    }
    let anchor = pairs[pairs.length - 1];
    for (const a of opts.after ?? []) {
      const p = pairs.find((x) => isScalar(x.key) && String(x.key.value) === a);
      if (p) anchor = p;
    }
    if (!anchor) throw new Error("cannot add to an empty mapping");
    if (flow) {
      this.insert(this.valueEnd(anchor), `, ${text}`);
    } else {
      const end = this.pairEnd(anchor);
      const lead = end > 0 && this.text[end - 1] !== "\n" ? this.eol : "";
      this.insert(end, `${lead}${" ".repeat(indent)}${text}${this.eol}`);
    }
  }

  /** Removes a key of a mapping, with its comment; comment lines above the next key stay. */
  remove(map: YAMLMap, key: string) {
    const pairs = map.items as Pair[];
    const i = pairs.findIndex((p) => isScalar(p.key) && String(p.key.value) === key);
    if (i < 0) return;
    const p = pairs[i];
    const keyStart = (p.key as Ranged).range![0];
    if (map.flow) {
      if (i > 0) this.replace(this.valueEnd(pairs[i - 1]), this.valueEnd(p), "");
      else if (pairs[i + 1]) this.replace(keyStart, (pairs[i + 1].key as Ranged).range![0], "");
      else this.replace(keyStart, this.valueEnd(p), "");
      return;
    }
    const start = this.lineStart(keyStart);
    if (this.text.slice(start, keyStart).trim() !== "") throw new Error(`cannot remove ${key}: it shares a line with another key`);
    this.replace(start, this.pairEnd(p), "");
  }

  /** Replaces a scalar node's text. */
  scalar(node: Scalar, text: string) {
    const r = (node as Ranged).range!;
    this.replace(r[0], r[1], text);
  }

  /** The text before an item's content on its first line (`  - `), and where the item's lines start. */
  itemPrefix(item: Ranged): { prefix: string; start: number } {
    const start = this.lineStart(item.range![0]);
    return { prefix: this.text.slice(start, item.range![0]), start };
  }

  /** Where a sequence item ends: after its last line. */
  itemEnd(item: Ranged): number {
    const e = item.range![2];
    return this.text[e - 1] === "\n" ? e : this.lineEnd(Math.max(e - 1, 0));
  }

  /** Removes a sequence item, the comment lines just above it and one blank line before them. */
  removeItem(seq: YAMLSeq, item: Ranged) {
    const { start } = this.itemPrefix(item);
    let from = start;
    for (;;) {
      if (from === 0) break;
      const prev = this.lineStart(from - 1);
      const line = this.text.slice(prev, from).trim();
      if (line.startsWith("#")) from = prev;
      else {
        if (line === "" && seq.items.indexOf(item as never) > 0) from = prev;
        break;
      }
    }
    this.replace(from, this.itemEnd(item), "");
  }

  /** The result, or an error when two changes overlap. */
  result(): string {
    // From the end backwards; at one place, the change made first ends up first in the text.
    const sorted = [...this.edits].sort((a, b) => b.start - a.start || b.end - a.end || b.n - a.n);
    let out = this.text;
    let floor = Infinity;
    for (const e of sorted) {
      if (e.end > floor) throw new Error("two changes overlap");
      out = out.slice(0, e.start) + e.text + out.slice(e.end);
      floor = e.start;
    }
    return out;
  }
}

export const isBlockMap = (n: unknown): n is YAMLMap => isMap(n) && !n.flow;
export const isBlockSeq = (n: unknown): n is YAMLSeq => isSeq(n) && !n.flow;

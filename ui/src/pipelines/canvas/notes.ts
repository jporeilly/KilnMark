/**
 * Notes on the canvas of a pipeline or a flow: the top-level `notes:` list of sticky notes, and the
 * short `note:` any node may have. Both are for people: the server checks their shape and limits and
 * never reads them for a run (CanvasNotes on the server), so writing or moving a note never changes
 * what runs. As with the layout, every change here rewrites only the note it changes and splices it
 * into the text (patch.ts): every other byte of the YAML stays as it was.
 *
 * A note attached to a node (`node: tidy`) is placed from that node's top-left corner, so it moves
 * with the node; a free-standing one from the canvas's origin.
 */
import { isMap, isScalar, isSeq, LineCounter, parseDocument, Scalar, type Document, type Pair, type YAMLMap, type YAMLSeq } from "yaml";
import { isBlockMap, isBlockSeq, Patch, scalarYaml } from "./patch.ts";

/** The colours a note may have, the first when it says none. Each is drawn with one of the theme's tints. */
export const NOTE_COLOURS = ["yellow", "blue", "green", "pink", "grey"] as const;
export type NoteColour = (typeof NOTE_COLOURS)[number];
export const COLOUR_WORDS: Record<NoteColour, string> = { yellow: "Yellow", blue: "Blue", green: "Green", pink: "Pink", grey: "Grey" };

/** As the server: most notes on one canvas, longest note, longest note of a node. */
export const MAX_NOTES = 50;
export const MAX_NOTE_TEXT = 2000;
export const MAX_NODE_NOTE = 280;
/** A new note's size, and the least and most a note may measure (the server's limits). */
export const NOTE_SIZE = { width: 240, height: 140 };
export const NOTE_MIN = { width: 120, height: 60 };
export const NOTE_MAX = { width: 1200, height: 1200 };
const MAX_PLACE = 100_000;

export const NOTES = "notes";

/**
 * One note as the canvas draws it. `index` is its place in the YAML list, which is how a change
 * names it; `node` the node it is attached to, or null.
 */
export type CanvasNote = {
  index: number;
  text: string;
  node: string | null;
  x: number;
  y: number;
  width: number;
  height: number;
  colour: NoteColour;
};

/** What a change may set on a note. */
export type NotePatch = Partial<Pick<CanvasNote, "text" | "node" | "x" | "y" | "width" | "height" | "colour">>;

export type Change = { text: string; id?: string } | { error: string };

const CANNOT_READ = "The YAML cannot be read, so the canvas cannot change its notes. Fix the YAML first.";

const clamp = (v: number, least: number, most: number) => Math.min(most, Math.max(least, v));

const numberOf = (n: unknown): number | null => {
  const v = isScalar(n) ? n.value : null;
  return typeof v === "number" && Number.isFinite(v) ? v : null;
};

const textOf = (n: unknown): string | null => (isScalar(n) && n.value != null && typeof n.value !== "object" ? String(n.value) : null);

/** The notes a document's `notes:` list holds, as the canvas draws them; anything it cannot read is left out. */
export function readNotes(doc: Document): CanvasNote[] {
  const top = doc.contents;
  if (!isMap(top)) return [];
  const list = top.get(NOTES, true);
  if (!isSeq(list)) return [];
  const out: CanvasNote[] = [];
  list.items.forEach((item, index) => {
    if (!isMap(item) || index >= MAX_NOTES) return;
    const colour = textOf(item.get("colour", true));
    const node = textOf(item.get("node", true));
    out.push({
      index,
      text: textOf(item.get("text", true)) ?? "",
      node: node && node.trim() !== "" ? node : null,
      x: clamp(numberOf(item.get("x", true)) ?? 0, -MAX_PLACE, MAX_PLACE),
      y: clamp(numberOf(item.get("y", true)) ?? 0, -MAX_PLACE, MAX_PLACE),
      width: clamp(numberOf(item.get("width", true)) ?? NOTE_SIZE.width, NOTE_MIN.width, NOTE_MAX.width),
      height: clamp(numberOf(item.get("height", true)) ?? NOTE_SIZE.height, NOTE_MIN.height, NOTE_MAX.height),
      colour: (NOTE_COLOURS as readonly string[]).includes(colour ?? "") ? (colour as NoteColour) : "yellow",
    });
  });
  return out;
}

/** A node's own note, as written, or null. */
export function nodeNoteOf(map: YAMLMap): string | null {
  const t = textOf(map.get("note", true));
  return t && t.trim() !== "" ? t : null;
}

/** Where a note is drawn: from its node's corner when it is attached to one the canvas has, else from the origin. */
export function placeOf(note: Pick<CanvasNote, "node" | "x" | "y">, anchors: Record<string, { x: number; y: number }>) {
  const at = note.node ? anchors[note.node] : undefined;
  return at ? { x: at.x + note.x, y: at.y + note.y } : { x: note.x, y: note.y };
}

/** A note's text as a person may keep it: no control characters but line breaks and tabs, no trailing space, within the limit. */
export function cleanNoteText(text: string, most = MAX_NOTE_TEXT): string {
  // eslint-disable-next-line no-control-regex
  return text
    .replace(/\r\n?/g, "\n")
    .replace(/[\u0000-\u0008\u000b-\u001f\u007f\u2028\u2029]/g, "")
    .replace(/^(?:[ \t]*\n)+/, "")
    .replace(/\s+$/, "")
    .slice(0, most);
}

/** A node's note: one line, no control characters, within its limit. */
export function cleanNodeNote(text: string): string {
  // eslint-disable-next-line no-control-regex
  return text.replace(/[\r\n\t\u2028\u2029]+/g, " ").replace(/[\u0000-\u001f\u007f]/g, "").trim().slice(0, MAX_NODE_NOTE);
}

type Ranged = { range: [number, number, number] };

const pairOf = (map: YAMLMap, key: string) =>
  (map.items as Pair[]).find((p) => isScalar(p.key) && String(p.key.value) === key) ?? null;

/** The note's text as YAML at `indent` (the column its key starts at): a literal block over several lines, else one scalar. */
function textYaml(text: string, indent: number, eol: string): string {
  if (!text.includes("\n")) return scalarYaml(text);
  // A literal block keeps the lines as typed; one starting with a space, or holding a tab, is double-quoted instead.
  if (/^\n|^[ \t]/.test(text) || text.includes("\t")) return scalarYaml(text, true);
  const pad = " ".repeat(indent + 2);
  return `|-${eol}${text.split("\n").map((l) => (l === "" ? "" : pad + l)).join(eol)}`;
}

/** The keys of a note in the order the canvas writes them, leaving out what is as a new note has it. */
function noteFields(n: Omit<CanvasNote, "index">): [string, string][] {
  const out: [string, string][] = [];
  if (n.node) out.push(["node", scalarYaml(n.node)]);
  out.push(["x", String(Math.round(n.x))], ["y", String(Math.round(n.y))]);
  out.push(["width", String(Math.round(n.width))], ["height", String(Math.round(n.height))]);
  out.push(["colour", n.colour]);
  return out;
}

/** A whole note as a block item at `prefix` (`  - `). */
function noteItem(n: Omit<CanvasNote, "index">, prefix: string, eol: string): string {
  const pad = " ".repeat(prefix.length);
  const lines = [`${prefix}text: ${textYaml(n.text, prefix.length, eol)}`];
  for (const [k, v] of noteFields(n)) lines.push(`${pad}${k}: ${v}`);
  return lines.join(eol);
}

type Ctx = { doc: Document; p: Patch; top: YAMLMap; notes: CanvasNote[] };

/** Makes a change to the notes as text edits; refused rather than written when the result would not read back. */
function change(text: string, fn: (ctx: Ctx) => string | void): Change {
  const doc = parseDocument(text);
  if (doc.errors.length) return { error: CANNOT_READ };
  if (!isMap(doc.contents)) return { error: "The YAML is not a pipeline or a flow, so the canvas cannot change its notes." };
  const p = new Patch(text);
  try {
    const id = fn({ doc, p, top: doc.contents, notes: readNotes(doc) });
    const out = p.result();
    if (parseDocument(out).errors.length) {
      return { error: "The canvas could not change this note without breaking the YAML. Change it in the YAML tab." };
    }
    return { text: out, id: id ?? undefined };
  } catch (e) {
    return { error: (e as Error).message };
  }
}

function listOf(ctx: Ctx): { pair: Pair | null; seq: YAMLSeq | null } {
  const pair = pairOf(ctx.top, NOTES);
  return { pair, seq: pair && isSeq(pair.value) ? pair.value : null };
}

function itemAt(ctx: Ctx, index: number): YAMLMap {
  const { seq } = listOf(ctx);
  const item = seq?.items[index];
  if (!seq || !isMap(item)) throw new Error("That note is not there any more. It may have been changed in the YAML.");
  if (!isBlockSeq(seq)) throw new Error("The notes are written in flow style ([ … ]). Change them in the YAML tab.");
  return item;
}

/**
 * Adds a note, after the others; the first one starts a `notes:` list at the end of the YAML under
 * `comment` (which says Kilnmark ignores it). Its id is its index, as text.
 */
export function addNote(text: string, note: Omit<CanvasNote, "index">, comment: string): Change {
  return change(text, (ctx) => {
    const { p, top } = ctx;
    const { pair, seq } = listOf(ctx);
    const clean = { ...note, text: cleanNoteText(note.text) };
    if (seq && seq.items.length >= MAX_NOTES) throw new Error(`A canvas has at most ${MAX_NOTES} notes. Remove one first.`);
    if (seq && seq.items.length > 0) {
      if (!isBlockSeq(seq)) throw new Error("The notes are written in flow style ([ … ]). Add the note in the YAML tab.");
      const items = seq.items as unknown as Ranged[];
      const last = items[items.length - 1];
      const { prefix } = p.itemPrefix(items[0]);
      const end = p.itemEnd(last);
      p.insert(end, `${p.text[end - 1] === "\n" ? "" : p.eol}${noteItem(clean, prefix, p.eol)}${p.eol}`);
      return String(seq.items.length);
    }
    const body = noteItem(clean, "  - ", p.eol);
    if (pair) {
      // `notes:` with nothing (or `[]`) after it: the list is written in its place.
      p.replace((pair.key as unknown as Ranged).range[0], p.valueEnd(pair), `${NOTES}:${p.eol}${body}`);
      return "0";
    }
    const end = (top as unknown as Ranged).range[1];
    const lead = end > 0 && p.text[end - 1] !== "\n" ? p.eol : "";
    p.insert(end, `${lead}${p.eol}#${comment}${p.eol}${NOTES}:${p.eol}${body}${p.eol}`);
    return "0";
  });
}

/** Writes `next` over the note `item`: each key in place where it can be, else the whole item again. */
function writeNote(ctx: Ctx, item: YAMLMap, was: CanvasNote, next: Omit<CanvasNote, "index">) {
  const { p } = ctx;
  if (isBlockMap(item) && item.items.length > 0) {
    // Tried on its own first, so a key it cannot change in place leaves nothing half written.
    const trial = new Patch(p.text);
    try {
      const keyCol = trial.col(((item.items as Pair[])[0].key as unknown as Ranged).range[0]);
      const textPair = pairOf(item, "text");
      if (textPair && next.text.includes("\n")) {
        // A comment after the text would end up inside a block of lines: the note is written again whole.
        const end = trial.valueEnd(textPair);
        if (trial.text.slice(end, trial.lineEnd(end)).trim() !== "") throw new Error("a comment follows the text");
      }
      if (next.text !== was.text || !textPair) {
        trial.put(item, "text", null, { raw: textYaml(next.text, keyCol, trial.eol) });
      }
      if (next.node !== was.node) {
        if (next.node) trial.put(item, "node", null, { raw: scalarYaml(next.node), after: ["text"] });
        else trial.remove(item, "node");
      }
      for (const k of ["x", "y", "width", "height"] as const) {
        if (Math.round(next[k]) !== Math.round(was[k]) || !pairOf(item, k)) trial.put(item, k, null, { raw: String(Math.round(next[k])) });
      }
      if (next.colour !== was.colour || !pairOf(item, "colour")) trial.put(item, "colour", null, { raw: next.colour });
      for (const e of trial.edits) p.replace(e.start, e.end, e.text);
      return;
    } catch {
      // Written again whole, below.
    }
  }
  const { prefix, start } = p.itemPrefix(item as unknown as Ranged);
  const end = p.itemEnd(item as unknown as Ranged);
  p.replace(start, end, `${noteItem(next, prefix.replace(/[^\s-]/g, " "), p.eol)}${p.eol}`);
}

/** Changes one note: its text, colour, place, size or the node it is attached to. */
export function updateNote(text: string, index: number, patch: NotePatch): Change {
  return change(text, (ctx) => {
    const item = itemAt(ctx, index);
    const was = ctx.notes.find((n) => n.index === index);
    if (!was) throw new Error("That note is not there any more. It may have been changed in the YAML.");
    const next = { ...was, ...patch };
    if (patch.text != null) next.text = cleanNoteText(patch.text);
    writeNote(ctx, item, was, next);
    return String(index);
  });
}

/** Removes a note; the last one takes the `notes:` list (and the comment the canvas wrote above it) with it. */
export function removeNote(text: string, index: number, comment: string): Change {
  return change(text, (ctx) => {
    const item = itemAt(ctx, index);
    const { pair, seq } = listOf(ctx);
    const { p } = ctx;
    if (seq!.items.length > 1) {
      p.removeItem(seq!, item as unknown as Ranged);
      return;
    }
    let start = p.lineStart((pair!.key as unknown as Ranged).range[0]);
    const above = start > 0 ? p.lineStart(start - 1) : start;
    if (start > 0 && p.text.slice(above, start).trim() === `#${comment}`.trim()) {
      start = above;
      const blank = start > 0 ? p.lineStart(start - 1) : start;
      if (start > 0 && p.text.slice(blank, start).trim() === "") start = blank;
    }
    p.replace(start, p.pairEnd(pair!), "");
  });
}

/** The node maps of a pipeline or flow: under nodes, or steps before the rename. */
function nodeMaps(top: YAMLMap): YAMLMap[] {
  const list = top.get("nodes", true) ?? top.get("steps", true);
  return isSeq(list) ? list.items.filter((i): i is YAMLMap => isMap(i)) : [];
}

const idOf = (m: YAMLMap) => textOf(m.get("id", true));

/** Sets a node's own note (after its type), or removes it when blank. */
export function setNodeNote(text: string, id: string, note: string): Change {
  return change(text, (ctx) => {
    const map = nodeMaps(ctx.top).find((m) => idOf(m) === id);
    if (!map) throw new Error(`There is no node ${id}.`);
    const value = cleanNodeNote(note);
    if (value === "") {
      ctx.p.remove(map, "note");
      return;
    }
    ctx.p.put(map, "note", null, { raw: scalarYaml(value, !!map.flow && /[,[\]{}]/.test(value)), after: ["id", "type"] });
  });
}

/** Within a change of a node's id: the notes attached to it follow it (text edits on `p`). */
export function renameNoteNode(doc: Document, p: Patch, from: string, to: string) {
  const top = doc.contents;
  if (!isMap(top)) return;
  const list = top.get(NOTES, true);
  if (!isSeq(list)) return;
  for (const item of list.items) {
    if (!isMap(item)) continue;
    const node = item.get("node", true);
    if (isScalar(node) && String(node.value) === from) p.scalar(node, scalarYaml(to));
  }
}

/**
 * The notes attached to a node that is going, made free-standing where they are drawn now (`at`,
 * the node's corner on the canvas), so removing a node never loses what its notes say.
 */
export function detachNotes(text: string, id: string, at: { x: number; y: number } | null): Change {
  return change(text, (ctx) => {
    for (const n of ctx.notes) {
      if (n.node !== id) continue;
      const item = itemAt(ctx, n.index);
      writeNote(ctx, item, n, { ...n, node: null, x: (at?.x ?? 0) + n.x, y: (at?.y ?? 0) + n.y });
    }
  });
}

// ---- comparing versions ----

const CANVAS_COMMENT = /^#.*Kilnmark ignores this/;

/**
 * The root mapping when it is written as blocks: every top-level key at the start of a line of its
 * own. Null for YAML that cannot be read, a root in braces, or anything else.
 */
function blockRoot(text: string, lines: string[]): { top: YAMLMap; keyLines: Map<string, number>; counter: LineCounter } | null {
  const counter = new LineCounter();
  const doc = parseDocument(text, { lineCounter: counter });
  if (doc.errors.length || !isMap(doc.contents) || doc.contents.flow) return null;
  const keyLines = new Map<string, number>();
  const seen = new Set<number>();
  for (const p of doc.contents.items as Pair[]) {
    const key = p.key as (Scalar & Ranged) | null;
    if (!isScalar(key) || !key.range) return null;
    const pos = counter.linePos(key.range[0]);
    const line = pos.line - 1;
    const name = String(key.value);
    if (pos.col !== 1 || seen.has(line) || !(lines[line] ?? "").startsWith(name)) return null;
    seen.add(line);
    keyLines.set(name, line);
  }
  return { top: doc.contents, keyLines, counter };
}

/** The lines (from 0) of each node's `note:` written on lines of its own: its key's line and those under it indented further. */
function nodeNoteLines(top: YAMLMap, counter: LineCounter, lines: string[]): Set<number> {
  const out = new Set<number>();
  for (const map of nodeMaps(top)) {
    // A node in braces may go on over several lines: its note is left in, so nothing else goes with it.
    if (map.flow) continue;
    const pair = pairOf(map, "note");
    const key = pair?.key as (Scalar & Ranged) | undefined;
    if (!key?.range) continue;
    const pos = counter.linePos(key.range[0]);
    const line = pos.line - 1;
    const col = pos.col - 1;
    if (line < 0 || line >= lines.length || lines[line].slice(0, col).trim() !== "") continue;
    out.add(line);
    let blanks: number[] = [];
    for (let i = line + 1; i < lines.length; i++) {
      const l = lines[i];
      if (l.trim() === "") blanks.push(i);
      else if (l.length - l.trimStart().length > col) {
        blanks.forEach((b) => out.add(b));
        blanks = [];
        out.add(i);
      } else break;
    }
  }
  return out;
}

/**
 * The YAML without what only the canvas reads: the `layout:` and `notes:` blocks (with the comment
 * the canvas writes above each, and the blank lines before it) and each node's `note:`. What decides
 * whether a change is more than moving nodes and writing notes. Only a mapping written as blocks is
 * taken apart, by where the parser found each key; any other YAML is compared as written, so a real
 * change is never hidden. The server compares versions the same way (CanvasNotes.withoutCanvas).
 */
export function withoutCanvas(yaml: string): string {
  // As the parser counts lines: a lone CR ends a line too.
  const text = yaml.replace(/\r\n?/g, "\n");
  const lines = text.split("\n");
  const root = blockRoot(text, lines);
  const drop = new Set<number>();
  if (root) {
    nodeNoteLines(root.top, root.counter, lines).forEach((l) => drop.add(l));
    const starts = [...root.keyLines.values()].sort((a, b) => a - b);
    for (const key of ["layout", NOTES]) {
      const at = root.keyLines.get(key);
      if (at == null) continue;
      const end = starts.find((l) => l > at) ?? lines.length;
      let start = at;
      while (start > 0 && CANVAS_COMMENT.test(lines[start - 1])) start--;
      while (start > 0 && lines[start - 1].trim() === "") start--;
      for (let i = start; i < end; i++) drop.add(i);
    }
  }
  return lines.filter((_, i) => !drop.has(i)).join("\n").replace(/\n*$/, "\n");
}
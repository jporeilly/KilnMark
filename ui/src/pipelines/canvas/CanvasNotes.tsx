import { useEffect, useId, useLayoutEffect, useRef, useState, type KeyboardEvent, type PointerEvent as ReactPointerEvent } from "react";
import { createPortal } from "react-dom";
import { useStore, ViewportPortal } from "@xyflow/react";
import { NoteText } from "./NoteText";
import {
  cleanNodeNote,
  COLOUR_WORDS,
  MAX_NODE_NOTE,
  MAX_NOTE_TEXT,
  NOTE_COLOURS,
  NOTE_MAX,
  NOTE_MIN,
  placeOf,
  type CanvasNote,
  type NoteColour,
  type NotePatch,
} from "./notes";

type Point = { x: number; y: number };

export type CanvasNotesProps = {
  notes: CanvasNote[];
  /** Where each node is drawn now (its top-left corner), for the notes attached to one. */
  anchors: Record<string, Point>;
  /** The nodes a note may be attached to, in order. */
  nodeIds: string[];
  /** Designers and admins change notes; everyone else reads them. */
  editable: boolean;
  /** "pipeline" or "flow", for the words. */
  what: string;
  /** One change to a note: one undo step. `coalesce` merges quick changes of one note (moving it with the keys). */
  onUpdate: (index: number, patch: NotePatch, label: string) => void;
  onRemove: (index: number, label: string) => void;
  /** Set by Add a note: focus this note and open its editor, once drawn. */
  focusRequest?: { index: number; n: number } | null;
  /** After a note is removed: where the focus goes. */
  onRemoved?: () => void;
};

/** How far one press of Shift and an arrow key moves or resizes a note, in canvas pixels. */
const STEP = 20;
/** Moves made with the keys are written once they have been still this long: one undo step. */
const KEY_SETTLE_MS = 400;

const words = (text: string, most = 60) => {
  const plain = text
    .replace(/!?\[([^\]]*)\]\([^)]*\)/g, "$1")
    .replace(/[*_`#>[\]()]/g, "")
    .replace(/\s+/g, " ")
    .trim();
  return plain.length > most ? `${plain.slice(0, most - 1).trimEnd()}…` : plain || "empty";
};

const clampSize = (w: number, h: number) => ({
  width: Math.round(Math.min(NOTE_MAX.width, Math.max(NOTE_MIN.width, w))),
  height: Math.round(Math.min(NOTE_MAX.height, Math.max(NOTE_MIN.height, h))),
});

/** Keeps the pointer's moves for the note while it is dragged (where the browser can). */
function capture(e: ReactPointerEvent<HTMLElement>, on: boolean) {
  try {
    if (on) e.currentTarget.setPointerCapture?.(e.pointerId);
    else e.currentTarget.releasePointerCapture?.(e.pointerId);
  } catch {
    // Not a pointer the browser knows (a synthetic event): the moves still arrive on the note.
  }
}

/** A press that belongs to something inside the note: a link, a field or a button. */
const inControl = (el: EventTarget | null) =>
  el instanceof Element && !!el.closest("a, button, input, textarea, select, label, .cv-note-editor");

/**
 * The notes on a canvas (pipelines' and flows' alike), drawn in the canvas's own coordinates under
 * its nodes, so they pan and zoom with it. Each is announced as a note, never as a node, and takes
 * no part in edges, connecting or the node counts. With `editable`, a note is moved by dragging it
 * (or Shift and the arrow keys), resized from its corner (or Ctrl, Shift and the arrow keys), edited
 * with a double-click or Enter (Escape stops editing), and removed with Delete; each change is one
 * undo step, made by the page as a change to the YAML.
 */
export function CanvasNotes(props: CanvasNotesProps) {
  if (props.notes.length === 0) return null;
  return (
    <ViewportPortal>
      <NotesLayer {...props} />
    </ViewportPortal>
  );
}

function NotesLayer({ notes, anchors, nodeIds, editable, what, onUpdate, onRemove, focusRequest, onRemoved }: CanvasNotesProps) {
  const zoom = useStore((s) => s.transform[2]);
  const idBase = useId();
  const layer = useRef<HTMLDivElement>(null);
  const [editing, setEditing] = useState<number | null>(null);
  // While a note is dragged or its keys pressed: where it is shown, before the change is written.
  const [live, setLive] = useState<{ index: number; x: number; y: number; width: number; height: number } | null>(null);
  const drag = useRef<{ index: number; mode: "move" | "size"; sx: number; sy: number; from: CanvasNote; moved: boolean } | null>(null);
  const keyTimer = useRef<number | undefined>(undefined);
  const keyMove = useRef<{ index: number; patch: NotePatch; label: string } | null>(null);

  const flushKeys = () => {
    window.clearTimeout(keyTimer.current);
    const k = keyMove.current;
    keyMove.current = null;
    if (k) {
      onUpdate(k.index, k.patch, k.label);
      // Drawn from the YAML again: where the change put it, or where it was if the change was refused.
      setLive(null);
    }
  };
  const flushRef = useRef(flushKeys);
  flushRef.current = flushKeys;
  useEffect(() => () => flushRef.current(), []);
  // A shown place is dropped once the YAML has the change.
  useEffect(() => {
    if (!drag.current && !keyMove.current) setLive(null);
  }, [notes]);
  // A note being edited that has gone (undone, removed in the YAML) is no longer edited.
  useEffect(() => {
    if (editing != null && !notes.some((n) => n.index === editing)) setEditing(null);
  }, [notes, editing]);

  const elementOf = (index: number) => layer.current?.querySelector<HTMLElement>(`[data-note-index="${index}"]`) ?? null;
  useEffect(() => {
    if (!focusRequest) return;
    const t = window.setTimeout(() => {
      elementOf(focusRequest.index)?.focus();
      if (editable) setEditing(focusRequest.index);
    }, 30);
    return () => window.clearTimeout(t);
    // Only a new request moves the focus.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [focusRequest?.n]);

  const startDrag = (e: ReactPointerEvent<HTMLElement>, note: CanvasNote, mode: "move" | "size") => {
    if (!editable || e.button !== 0 || editing === note.index) return;
    if (mode === "move" && inControl(e.target)) return;
    e.stopPropagation();
    flushKeys();
    capture(e, true);
    drag.current = { index: note.index, mode, sx: e.clientX, sy: e.clientY, from: note, moved: false };
  };
  const moveDrag = (e: ReactPointerEvent<HTMLElement>) => {
    const d = drag.current;
    if (!d) return;
    const dx = (e.clientX - d.sx) / (zoom || 1);
    const dy = (e.clientY - d.sy) / (zoom || 1);
    if (!d.moved && Math.abs(dx) + Math.abs(dy) < 3) return;
    d.moved = true;
    const f = d.from;
    if (d.mode === "move") setLive({ index: f.index, x: f.x + dx, y: f.y + dy, width: f.width, height: f.height });
    else setLive({ index: f.index, x: f.x, y: f.y, ...clampSize(f.width + dx, f.height + dy) });
  };
  const endDrag = (e: ReactPointerEvent<HTMLElement>) => {
    const d = drag.current;
    drag.current = null;
    if (!d) return;
    capture(e, false);
    if (!d.moved) {
      elementOf(d.index)?.focus();
      return;
    }
    const dx = (e.clientX - d.sx) / (zoom || 1);
    const dy = (e.clientY - d.sy) / (zoom || 1);
    const n = d.index + 1;
    if (d.mode === "move") onUpdate(d.index, { x: Math.round(d.from.x + dx), y: Math.round(d.from.y + dy) }, `Moved note ${n}.`);
    else {
      const s = clampSize(d.from.width + dx, d.from.height + dy);
      onUpdate(d.index, s, `Resized note ${n} to ${s.width} by ${s.height}.`);
    }
    setLive(null);
  };

  const onKey = (e: KeyboardEvent<HTMLElement>, note: CanvasNote) => {
    if (e.target !== e.currentTarget) return;
    const k = e.key;
    const n = note.index + 1;
    const done = () => {
      e.preventDefault();
      e.stopPropagation();
    };
    if (!editable) return;
    if (k === "Enter" || k === "F2") {
      done();
      flushKeys();
      setEditing(note.index);
      return;
    }
    if (k === "Delete" || k === "Backspace") {
      done();
      flushKeys();
      onRemove(note.index, `Removed note ${n}.`);
      onRemoved?.();
      return;
    }
    if (k.startsWith("Arrow") && e.shiftKey) {
      done();
      const sx = k === "ArrowRight" ? STEP : k === "ArrowLeft" ? -STEP : 0;
      const sy = k === "ArrowDown" ? STEP : k === "ArrowUp" ? -STEP : 0;
      const base = live?.index === note.index ? live : note;
      const resize = e.ctrlKey || e.metaKey;
      const next = resize ? { ...base, ...clampSize(base.width + sx, base.height + sy) } : { ...base, x: base.x + sx, y: base.y + sy };
      setLive({ index: note.index, x: next.x, y: next.y, width: next.width, height: next.height });
      if (keyMove.current && keyMove.current.index !== note.index) flushKeys();
      keyMove.current = {
        index: note.index,
        patch: { x: Math.round(next.x), y: Math.round(next.y), width: next.width, height: next.height },
        label: resize ? `Resized note ${n} to ${next.width} by ${next.height}.` : `Moved note ${n}.`,
      };
      window.clearTimeout(keyTimer.current);
      keyTimer.current = window.setTimeout(flushKeys, KEY_SETTLE_MS);
    }
  };

  const help = `${idBase}-help`;
  return (
    <div className="cv-notes" ref={layer}>
      <p id={help} className="visually-hidden">
        {editable
          ? `A note on the canvas, for people: Kilnmark ignores it when it runs the ${what}. Enter edits it, Delete removes it, Shift and the arrow keys move it, Ctrl, Shift and the arrow keys resize it.`
          : `A note on the canvas, for people: Kilnmark ignores it when it runs the ${what}.`}
      </p>
      {notes.map((note) => {
        const shown = live?.index === note.index ? { ...note, ...live } : note;
        const at = placeOf(shown, anchors);
        const n = note.index + 1;
        const attached = note.node && anchors[note.node] ? note.node : null;
        const isEditing = editing === note.index;
        return (
          <div
            key={note.index}
            data-note-index={note.index}
            className={`cv-note cv-note-${note.colour} nodrag nopan${isEditing ? " is-editing" : ""}${editable ? " is-editable" : ""}`}
            style={{ transform: `translate(${at.x}px, ${at.y}px)`, width: shown.width, height: shown.height }}
            role="group"
            aria-roledescription="note"
            aria-label={`Note ${n}${attached ? `, on node ${attached}` : ""}: ${words(note.text)}`}
            aria-describedby={help}
            tabIndex={0}
            onPointerDown={(e) => startDrag(e, note, "move")}
            onPointerMove={moveDrag}
            onPointerUp={endDrag}
            onPointerCancel={() => {
              drag.current = null;
              setLive(null);
            }}
            onDoubleClick={(e) => {
              if (!editable || inControl(e.target)) return;
              e.stopPropagation();
              setEditing(note.index);
            }}
            onKeyDown={(e) => onKey(e, note)}
            onBlur={(e) => {
              const to = e.relatedTarget as Element | null;
              // The editor is drawn outside the canvas (over the page), but it is part of the note.
              if (e.currentTarget.contains(to) || to?.closest?.(`[data-note-editor="${note.index}"]`)) return;
              flushKeys();
              // The focus has gone elsewhere: editing stops, keeping what was typed.
              // (Not when the whole window loses the focus: the editor is there on coming back.)
              if (isEditing && (e.relatedTarget || document.hasFocus())) setEditing(null);
            }}
          >
            <div className="cv-note-body nowheel">
              <NoteText text={note.text} />
            </div>
            {editable && !isEditing && (
              <span className="cv-note-resize" aria-hidden="true" title="Drag to resize" onPointerDown={(e) => startDrag(e, note, "size")} onPointerMove={moveDrag} onPointerUp={endDrag} />
            )}
            {isEditing && (
              <NoteEditor
                note={note}
                n={n}
                at={() => elementOf(note.index)}
                nodeIds={nodeIds}
                anchors={anchors}
                what={what}
                onUpdate={onUpdate}
                onRemove={() => {
                  setEditing(null);
                  onRemove(note.index, `Removed note ${n}.`);
                  onRemoved?.();
                }}
                onClose={() => {
                  setEditing(null);
                  window.setTimeout(() => elementOf(note.index)?.focus(), 0);
                }}
              />
            )}
          </div>
        );
      })}
    </div>
  );
}

/**
 * The note's editor, beside the note and over the page (so neither the canvas's edge nor its zoom
 * cuts it short): its text, colour and the node it is attached to. The text is written when editing stops (Done, Escape, Ctrl+Enter or the
 * focus leaving), as one change; a colour or a node chosen is a change of its own.
 */
function NoteEditor({
  note,
  n,
  at,
  nodeIds,
  anchors,
  what,
  onUpdate,
  onRemove,
  onClose,
}: {
  note: CanvasNote;
  n: number;
  /** The note on the canvas, to put the editor beside. */
  at: () => HTMLElement | null;
  nodeIds: string[];
  anchors: Record<string, Point>;
  what: string;
  onUpdate: CanvasNotesProps["onUpdate"];
  onRemove: () => void;
  onClose: () => void;
}) {
  const id = useId();
  const [draft, setDraft] = useState(note.text);
  const area = useRef<HTMLTextAreaElement>(null);
  const draftRef = useRef(draft);
  draftRef.current = draft;
  const written = useRef(false);
  const form = useRef<HTMLFormElement>(null);
  const [place, setPlace] = useState<{ top: number; left: number } | null>(null);
  // Beside the note: to its right if there is room, else to its left, kept inside the window.
  useLayoutEffect(() => {
    const put = () => {
      const r = at()?.getBoundingClientRect();
      const w = form.current?.offsetWidth || 320;
      const h = form.current?.offsetHeight || 420;
      if (!r) return;
      const vw = Math.min(window.innerWidth, document.documentElement.clientWidth || window.innerWidth);
      const vh = Math.min(window.innerHeight, document.documentElement.clientHeight || window.innerHeight);
      const right = r.right + 8;
      const left = right + w <= vw - 8 ? right : Math.max(8, Math.min(r.left - w - 8, vw - w - 8));
      setPlace({ left, top: Math.max(8, Math.min(r.top, vh - h - 8)) });
    };
    put();
    window.addEventListener("resize", put);
    return () => window.removeEventListener("resize", put);
    // Placed once it opens, and again when the window changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  // Into the text once it is placed (a hidden field cannot take the focus).
  const placed = place != null;
  useEffect(() => {
    if (!placed) return;
    area.current?.focus();
    area.current?.select();
  }, [placed]);

  const writeText = () => {
    if (written.current) return;
    written.current = true;
    const text = draftRef.current;
    if (text.replace(/\s+$/, "") !== note.text) onUpdate(note.index, { text }, `Changed the text of note ${n}.`);
  };
  const stop = () => {
    writeText();
    onClose();
  };
  // Leaving the editor some other way (a click on the canvas) still keeps what was typed.
  const stopRef = useRef(writeText);
  stopRef.current = writeText;
  useEffect(() => () => stopRef.current(), []);

  const attachTo = (node: string) => {
    // Kept where it is drawn: its place from the new node's corner, or from the origin.
    const here = placeOf(note, anchors);
    const to = node ? anchors[node] : undefined;
    const place = to ? { x: here.x - to.x, y: here.y - to.y } : here;
    onUpdate(note.index, { node: node || null, x: Math.round(place.x), y: Math.round(place.y) }, node ? `Attached note ${n} to ${node}.` : `Note ${n} stands on its own now.`);
  };

  return createPortal(
    <form
      ref={form}
      data-note-editor={note.index}
      className="cv-note-editor nodrag nopan nowheel"
      style={place ?? { visibility: "hidden" }}
      aria-label={`Edit note ${n}`}
      onSubmit={(e) => {
        e.preventDefault();
        stop();
      }}
      onPointerDown={(e) => e.stopPropagation()}
      onDoubleClick={(e) => e.stopPropagation()}
      onKeyDown={(e) => {
        e.stopPropagation();
        if (e.key === "Escape") {
          e.preventDefault();
          stop();
        } else if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) {
          e.preventDefault();
          stop();
        }
      }}
    >
      <label className="cv-label" htmlFor={`${id}-text`}>
        Note {n}
      </label>
      <textarea
        id={`${id}-text`}
        ref={area}
        rows={6}
        value={draft}
        maxLength={MAX_NOTE_TEXT}
        aria-describedby={`${id}-hint`}
        onChange={(e) => {
          written.current = false;
          setDraft(e.target.value);
        }}
      />
      <p id={`${id}-hint`} className="hint">
        {draft.length} of {MAX_NOTE_TEXT} characters. **Bold**, *italic*, lists (- or 1.) and links to https addresses. Never
        paste data values: notes are not masked. Escape or Ctrl+Enter stops editing.
      </p>
      <fieldset className="cv-note-colours">
        <legend className="cv-label">Colour</legend>
        {NOTE_COLOURS.map((c: NoteColour) => (
          <label key={c} className={`cv-note-swatch cv-note-${c}`}>
            <input
              type="radio"
              name={`${id}-colour`}
              value={c}
              checked={note.colour === c}
              onChange={() => onUpdate(note.index, { colour: c }, `Made note ${n} ${COLOUR_WORDS[c].toLowerCase()}.`)}
            />
            {COLOUR_WORDS[c]}
          </label>
        ))}
      </fieldset>
      <div className="cv-field">
        <label className="cv-label" htmlFor={`${id}-node`}>
          Attached to
        </label>
        <select id={`${id}-node`} value={note.node ?? ""} aria-describedby={`${id}-node-hint`} onChange={(e) => attachTo(e.target.value)}>
          <option value="">No node: it stays where it is on the canvas</option>
          {note.node && !nodeIds.includes(note.node) && <option value={note.node}>{note.node} (not in the {what})</option>}
          {nodeIds.map((x) => (
            <option key={x} value={x}>
              {x}
            </option>
          ))}
        </select>
        <p id={`${id}-node-hint`} className="hint">
          A note attached to a node moves with it.
        </p>
      </div>
      <div className="buttons">
        <button type="submit">Done</button>
        <button
          type="button"
          className="cv-danger"
          onClick={() => {
            // What was typed goes with the note.
            written.current = true;
            onRemove();
          }}
        >
          Delete note
        </button>
      </div>
    </form>,
    document.body,
  );
}

/**
 * A node's own note in its settings, in full: plain text of at most 280 characters on one line,
 * written when the field is left (or Enter is pressed), as one change. Read-only for those who
 * cannot change the node.
 */
export function NodeNoteField({
  id,
  note,
  readOnly,
  onNote,
}: {
  id: string;
  note: string | null;
  readOnly: boolean;
  onNote: (text: string) => void;
}) {
  const [draft, setDraft] = useState(note ?? "");
  useEffect(() => setDraft(note ?? ""), [note]);
  const write = () => {
    const next = cleanNodeNote(draft);
    if (!readOnly && next !== (note ?? "")) onNote(next);
  };
  if (readOnly && !note) return null;
  return (
    <div className="cv-field">
      <label className="cv-label" htmlFor={id}>
        Note
      </label>
      <textarea
        id={id}
        className="cv-node-note-field"
        rows={2}
        value={draft}
        readOnly={readOnly}
        maxLength={MAX_NODE_NOTE}
        aria-describedby={`${id}-help`}
        onChange={(e) => setDraft(e.target.value.replace(/[\r\n]+/g, " "))}
        onBlur={write}
        onKeyDown={(e) => {
          if (e.key === "Enter") {
            e.preventDefault();
            write();
          }
        }}
      />
      <p className="hint" id={`${id}-help`}>
        {readOnly
          ? "Shown under the node's name on the canvas."
          : `Shown under the node's name on the canvas. Plain text, at most ${MAX_NODE_NOTE} characters (${draft.length} now). Never paste data values: notes are not masked.`}
      </p>
    </div>
  );
}

/** A node's own note on its card: one line, the whole of it on hover and while the card has the focus. */
export function NodeNoteLine({ note }: { note: string | null | undefined }) {
  if (!note) return null;
  return (
    <div className="cv-node-note" title={note}>
      {note}
    </div>
  );
}

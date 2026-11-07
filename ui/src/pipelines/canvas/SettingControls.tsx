import { useEffect, useId, useRef, useState, type ReactNode } from "react";
import type { PipelineColumn, Source, NodeTypeSetting } from "../../api";
import { Switch } from "../../components/ui";
import { defaultText } from "./insight";
import type { Value } from "./model";

/** What a control needs besides its setting: the columns it can offer, sources, and assets. */
export type ControlContext = {
  /** Columns arriving at the node (for a lookup: main and lookup sides; for a join: left and right); null when not known. */
  columns: { main: PipelineColumn[] | null; lookup: PipelineColumn[] | null };
  /** Which side a column setting names; the right-hand side of a column map may name the other. */
  side: "main" | "lookup" | null;
  sources: Source[] | null;
  /** For write nodes: the source must allow writing. */
  writing: boolean;
  /** Tables and files of the node's source, for the asset picker. */
  assets: string[] | null;
  nodeType: string | null;
  /** The parameters the pipeline declares, offered as `${name}` in expressions. */
  parameters?: string[];
};

export type ControlProps = {
  spec: NodeTypeSetting;
  /** A stable id for the control, unique on the page. */
  id: string;
  value: Value | undefined;
  /** The scalar as written in the YAML, for text that looks like a number (`007`). */
  text?: string;
  onChange: (v: Value | undefined) => void;
  readOnly: boolean;
  /** Validation problems about this setting, in words. */
  problems: string[];
  context: ControlContext;
};

const asText = (v: Value | undefined, text?: string) => (text != null ? text : v == null ? "" : typeof v === "object" ? "" : String(v));
const asList = (v: Value | undefined): string[] =>
  Array.isArray(v) ? v.map((x) => String(x)) : v == null || typeof v === "object" ? [] : [String(v)];
const asPairs = (v: Value | undefined): [string, string][] =>
  v && typeof v === "object" && !Array.isArray(v) ? Object.entries(v).map(([k, x]) => [k, x == null ? "" : String(x)]) : [];

/**
 * The label, help, default and problems around a control; `describedBy` ties them to it. A `wide`
 * control (SQL, an expression, a list of pairs or rules, a read's column checklist) takes the whole
 * row of the panel, whatever it holds at the moment, so a setting never changes width as it fills.
 */
function Field({
  spec,
  id,
  problems,
  children,
  group,
  wide,
}: {
  spec: NodeTypeSetting;
  id: string;
  problems: string[];
  children: (describedBy: string, invalid: boolean) => ReactNode;
  group?: boolean;
  wide?: boolean;
}) {
  const def = defaultText(spec);
  const described = [`${id}-help`, problems.length ? `${id}-problems` : null].filter(Boolean).join(" ");
  const label = (
    <>
      {spec.label}
      {spec.required ? <span className="cv-required"> (required)</span> : null}
    </>
  );
  // The box straight under its name and the help under the box, so the boxes of a row line up
  // however long each one's help is.
  const body = (
    <>
      {children(described, problems.length > 0)}
      {problems.length > 0 && (
        <ul id={`${id}-problems`} className="cv-field-problems">
          {problems.map((p, i) => (
            <li key={i}>{p}</li>
          ))}
        </ul>
      )}
      <p id={`${id}-help`} className="hint">
        {spec.help}
        {spec.help && !/[.!?]$/.test(spec.help) ? "." : ""}
        {/* The space outside the unbroken default, so the help wraps before it, not a word early. */}
        {def != null && (
          <>
            {" "}
            <span className="cv-default">Default: {def}.</span>
          </>
        )}
      </p>
    </>
  );
  const cls = wide ? "cv-field cv-field-wide" : "cv-field";
  return group ? (
    <fieldset className={cls} data-setting={spec.key}>
      <legend className="cv-label">{label}</legend>
      {body}
    </fieldset>
  ) : (
    <div className={cls} data-setting={spec.key}>
      <label className="cv-label" htmlFor={id}>
        {label}
      </label>
      {body}
    </div>
  );
}

/** A text box that keeps what is typed while it has the focus, and follows the YAML otherwise. */
function useDraft(outside: string) {
  const [draft, setDraft] = useState(outside);
  const focused = useRef(false);
  useEffect(() => {
    if (!focused.current) setDraft(outside);
  }, [outside]);
  return {
    draft,
    setDraft,
    focusProps: {
      onFocus: () => {
        focused.current = true;
      },
      onBlur: () => {
        focused.current = false;
        setDraft(outside);
      },
    },
  };
}

function TextControl(p: ControlProps & { listId?: string; mono?: boolean }) {
  const outside = asText(p.value, p.text);
  const { draft, setDraft, focusProps } = useDraft(outside);
  const def = defaultText(p.spec);
  return (
    <Field spec={p.spec} id={p.id} problems={p.problems}>
      {(describedBy, invalid) => (
        <input
          id={p.id}
          className={p.mono ? "mono" : undefined}
          value={draft}
          readOnly={p.readOnly}
          list={p.listId}
          spellCheck={false}
          autoComplete="off"
          placeholder={def ?? undefined}
          aria-describedby={describedBy}
          aria-invalid={invalid || undefined}
          {...focusProps}
          onChange={(e) => {
            setDraft(e.target.value);
            p.onChange(e.target.value === "" ? undefined : e.target.value);
          }}
        />
      )}
    </Field>
  );
}

/** A read's query (ADR 0028): SQL over several lines, in a monospace box. */
function SqlControl(p: ControlProps) {
  const outside = asText(p.value, p.text);
  const { draft, setDraft, focusProps } = useDraft(outside);
  return (
    <Field spec={p.spec} id={p.id} problems={p.problems} wide>
      {(describedBy, invalid) => (
        <textarea
          id={p.id}
          className="mono cv-sql"
          rows={5}
          value={draft}
          readOnly={p.readOnly}
          spellCheck={false}
          autoComplete="off"
          placeholder="SELECT ... FROM schema.table"
          aria-describedby={describedBy}
          aria-invalid={invalid || undefined}
          {...focusProps}
          onChange={(e) => {
            setDraft(e.target.value);
            p.onChange(e.target.value.trim() === "" ? undefined : e.target.value);
          }}
        />
      )}
    </Field>
  );
}

function NumberControl(p: ControlProps) {
  const outside = asText(p.value, p.text);
  const { draft, setDraft, focusProps } = useDraft(outside);
  const def = defaultText(p.spec);
  const bad = draft.trim() !== "" && !Number.isFinite(Number(draft));
  const problems = bad ? ["A number, such as 1000.", ...p.problems] : p.problems;
  return (
    <Field spec={p.spec} id={p.id} problems={problems}>
      {(describedBy, invalid) => (
        <input
          id={p.id}
          inputMode="decimal"
          className="num cv-number"
          value={draft}
          readOnly={p.readOnly}
          placeholder={def ?? undefined}
          aria-describedby={describedBy}
          aria-invalid={invalid || undefined}
          {...focusProps}
          onChange={(e) => {
            const v = e.target.value;
            setDraft(v);
            // The text as typed, so a long whole number keeps every digit.
            if (v.trim() === "") p.onChange(undefined);
            else if (Number.isFinite(Number(v))) p.onChange(v.trim());
          }}
        />
      )}
    </Field>
  );
}

function BooleanControl(p: ControlProps) {
  const def = p.spec.defaultValue === true;
  const on = typeof p.value === "boolean" ? p.value : p.value === "true" ? true : p.value === "false" ? false : def;
  return (
    <div className="cv-field" data-setting={p.spec.key}>
      <Switch
        id={p.id}
        label={p.spec.label}
        help={p.spec.help}
        checked={on}
        disabled={p.readOnly}
        defaultNote={`Default: ${def ? "on" : "off"}`}
        describedBy={p.problems.length ? `${p.id}-problems` : undefined}
        // Back to the default: the key is left out, so the YAML says only what differs.
        onChange={(next) => p.onChange(next === def ? undefined : next)}
      />
      {p.problems.length > 0 && (
        <ul id={`${p.id}-problems`} className="cv-field-problems">
          {p.problems.map((x, i) => (
            <li key={i}>{x}</li>
          ))}
        </ul>
      )}
    </div>
  );
}

function ChoiceControl(p: ControlProps) {
  const value = asText(p.value, p.text);
  const def = defaultText(p.spec);
  const choices = p.spec.choices.includes(value) || value === "" ? p.spec.choices : [...p.spec.choices, value];
  return (
    <Field spec={p.spec} id={p.id} problems={p.problems}>
      {(describedBy, invalid) => (
        <select
          id={p.id}
          value={value}
          disabled={p.readOnly}
          aria-describedby={describedBy}
          aria-invalid={invalid || undefined}
          onChange={(e) => p.onChange(e.target.value === "" ? undefined : e.target.value)}
        >
          <option value="">{def != null ? `Default (${def})` : "Not set"}</option>
          {choices.map((c) => (
            <option key={c} value={c}>
              {c}
            </option>
          ))}
        </select>
      )}
    </Field>
  );
}

function SourceControl(p: ControlProps) {
  const value = asText(p.value, p.text);
  const sources = p.context.sources;
  const known = sources?.some((s) => s.name === value);
  const writable = (s: Source) => s.settings.allow_writing === "true";
  return (
    <Field spec={p.spec} id={p.id} problems={p.problems}>
      {(describedBy, invalid) =>
        sources == null ? (
          <input
            id={p.id}
            value={value}
            readOnly={p.readOnly}
            aria-describedby={describedBy}
            aria-invalid={invalid || undefined}
            onChange={(e) => p.onChange(e.target.value || undefined)}
          />
        ) : (
          <select
            id={p.id}
            value={value}
            disabled={p.readOnly}
            aria-describedby={describedBy}
            aria-invalid={invalid || undefined}
            onChange={(e) => p.onChange(e.target.value || undefined)}
          >
            <option value="">Choose a source</option>
            {!known && value !== "" && <option value={value}>{value} (not a saved source)</option>}
            {sources.map((s) => (
              <option key={s.name} value={s.name}>
                {s.displayName && s.displayName !== s.name ? `${s.displayName} (${s.name})` : s.name}
                {p.context.writing && !writable(s) ? ", read-only" : ""}
              </option>
            ))}
          </select>
        )
      }
    </Field>
  );
}

function AssetControl(p: ControlProps) {
  const listId = `${p.id}-assets`;
  return (
    <>
      <TextControl {...p} listId={listId} mono />
      {p.context.assets && p.context.assets.length > 0 && (
        <datalist id={listId}>
          {p.context.assets.map((a) => (
            <option key={a} value={a} />
          ))}
        </datalist>
      )}
    </>
  );
}

/** Buttons that put a column's name into an expression at the caret: a keyboard-friendly palette. */
function ColumnButtons({ columns, onPick, label }: { columns: PipelineColumn[] | null; onPick: (name: string) => void; label: string }) {
  if (!columns || columns.length === 0) return null;
  return (
    <div className="cv-column-buttons" role="group" aria-label={label}>
      {columns.slice(0, 40).map((c) => (
        <button key={c.name} type="button" className="cv-chip mono" onClick={() => onPick(c.name)} title={c.type}>
          {c.name}
        </button>
      ))}
    </div>
  );
}

/** Buttons that put a parameter, as `${name}`, into an expression at the caret. */
function ParameterButtons({ names, onPick, label }: { names: string[]; onPick: (word: string) => void; label: string }) {
  if (names.length === 0) return null;
  return (
    <div className="cv-column-buttons" role="group" aria-label={label}>
      {names.map((n) => (
        <button key={n} type="button" className="cv-chip cv-chip-param mono" onClick={() => onPick(`\${${n}}`)} title="A parameter: its value goes in as a value">
          {`\${${n}}`}
        </button>
      ))}
    </div>
  );
}

const quoteColumn = (name: string) => (/^[A-Za-z_][A-Za-z0-9_]*$/.test(name) ? name : `"${name.replace(/"/g, '""')}"`);

function insertAt(el: HTMLTextAreaElement | HTMLInputElement | null, current: string, word: string): { text: string; caret: number } {
  const start = el?.selectionStart ?? current.length;
  const end = el?.selectionEnd ?? current.length;
  const before = current.slice(0, start);
  const pad = before && !/\s$/.test(before) && !/[(,]$/.test(before) ? " " : "";
  const text = before + pad + word + current.slice(end);
  return { text, caret: (before + pad + word).length };
}

function ExpressionControl(p: ControlProps) {
  const outside = asText(p.value, p.text);
  const { draft, setDraft, focusProps } = useDraft(outside);
  const area = useRef<HTMLTextAreaElement>(null);
  const cols = p.context.columns.main;
  const insert = (word: string) => {
    const { text, caret } = insertAt(area.current, draft, word);
    setDraft(text);
    p.onChange(text);
    requestAnimationFrame(() => {
      area.current?.focus();
      area.current?.setSelectionRange(caret, caret);
    });
  };
  return (
    <Field spec={p.spec} id={p.id} problems={p.problems} wide>
      {(describedBy, invalid) => (
        <>
          <textarea
            id={p.id}
            ref={area}
            className="mono cv-expression"
            rows={2}
            value={draft}
            readOnly={p.readOnly}
            spellCheck={false}
            aria-describedby={describedBy}
            aria-invalid={invalid || undefined}
            {...focusProps}
            onChange={(e) => {
              setDraft(e.target.value);
              p.onChange(e.target.value.trim() === "" ? undefined : e.target.value);
            }}
          />
          {!p.readOnly && (
            <>
              <ColumnButtons columns={cols} label={`Insert a column into ${p.spec.label}`} onPick={(name) => insert(quoteColumn(name))} />
              <ParameterButtons names={p.context.parameters ?? []} label={`Insert a parameter into ${p.spec.label}`} onPick={insert} />
            </>
          )}
        </>
      )}
    </Field>
  );
}

/** Columns from a list when they are known, else typed with suggestions. */
function ColumnInput({
  id,
  value,
  columns,
  onChange,
  readOnly,
  label,
  describedBy,
  invalid,
  free,
}: {
  id: string;
  value: string;
  columns: PipelineColumn[] | null;
  onChange: (v: string) => void;
  readOnly: boolean;
  label?: string;
  describedBy?: string;
  invalid?: boolean;
  /** Any name may be typed (a new name for a rename): suggestions only. */
  free?: boolean;
}) {
  const listId = `${id}-cols`;
  if (columns && columns.length > 0 && !free) {
    const known = columns.some((c) => c.name === value);
    return (
      <select
        id={id}
        value={value}
        disabled={readOnly}
        aria-label={label}
        aria-describedby={describedBy}
        aria-invalid={invalid || undefined}
        onChange={(e) => onChange(e.target.value)}
      >
        <option value="">Choose a column</option>
        {!known && value !== "" && <option value={value}>{value} (not arriving here)</option>}
        {columns.map((c) => (
          <option key={c.name} value={c.name}>
            {c.name} ({c.type})
          </option>
        ))}
      </select>
    );
  }
  return (
    <>
      <input
        id={id}
        className="mono"
        value={value}
        readOnly={readOnly}
        list={columns?.length ? listId : undefined}
        aria-label={label}
        aria-describedby={describedBy}
        aria-invalid={invalid || undefined}
        spellCheck={false}
        autoComplete="off"
        onChange={(e) => onChange(e.target.value)}
      />
      {columns && columns.length > 0 && (
        <datalist id={listId}>
          {columns.map((c) => (
            <option key={c.name} value={c.name} />
          ))}
        </datalist>
      )}
    </>
  );
}

function ColumnControl(p: ControlProps) {
  const cols = p.context.side === "lookup" ? p.context.columns.lookup : p.context.columns.main;
  return (
    <Field spec={p.spec} id={p.id} problems={p.problems}>
      {(describedBy, invalid) => (
        <ColumnInput
          id={p.id}
          value={asText(p.value, p.text)}
          columns={cols}
          readOnly={p.readOnly}
          describedBy={describedBy}
          invalid={invalid}
          onChange={(v) => p.onChange(v === "" ? undefined : v)}
        />
      )}
    </Field>
  );
}

/**
 * A read node's columns: one box per column of the table, in the table's order, with its type.
 * None ticked reads them all; ticking adds a column at the end, so the order is the order chosen.
 */
function ColumnChecklist(p: ControlProps & { columns: PipelineColumn[] }) {
  const chosen = asList(p.value);
  const missing = chosen.filter((c) => !p.columns.some((x) => x.name === c));
  const toggle = (name: string, on: boolean) => {
    const next = on ? [...chosen, name] : chosen.filter((c) => c !== name);
    p.onChange(next.length ? next : undefined);
  };
  return (
    <Field spec={p.spec} id={p.id} problems={p.problems} group wide>
      {(describedBy, invalid) => (
        <>
          {/* The box under the name as in every setting; what it reads, in words, under the box. */}
          <ul className="cv-checklist" id={p.id} aria-describedby={`${describedBy} ${p.id}-state`} aria-invalid={invalid || undefined}>
            {p.columns.map((c) => {
              const at = chosen.indexOf(c.name);
              return (
                <li key={c.name}>
                  <label>
                    <input type="checkbox" checked={at >= 0} disabled={p.readOnly} onChange={(e) => toggle(c.name, e.target.checked)} />
                    <span className="mono">{c.name}</span> <span className="small muted">{c.type}</span>
                    {at >= 0 && chosen.length > 1 && <span className="small muted"> · {at + 1}</span>}
                  </label>
                </li>
              );
            })}
            {missing.map((c) => (
              <li key={c}>
                <label>
                  <input type="checkbox" checked disabled={p.readOnly} onChange={() => toggle(c, false)} />
                  <span className="mono">{c}</span> <span className="small pl-bad">not a column of the table</span>
                </label>
              </li>
            ))}
          </ul>
          <p className="small muted" id={`${p.id}-state`}>
            {chosen.length === 0 ? "None ticked: every column is read." : `${chosen.length} of ${p.columns.length} read, in this order: ${chosen.join(", ")}.`}
          </p>
          {!p.readOnly && chosen.length > 0 && (
            <button type="button" className="cv-mini" onClick={() => p.onChange(undefined)}>
              Read every column
            </button>
          )}
        </>
      )}
    </Field>
  );
}

/** An ordered list of columns: each can be removed; new ones are added from the arriving columns. */
function ColumnsControl(p: ControlProps) {
  const chosen = asList(p.value);
  const cols = p.context.side === "lookup" ? p.context.columns.lookup : p.context.columns.main;
  const [adding, setAdding] = useState("");
  const addId = `${p.id}-add`;
  const rest = cols?.filter((c) => !chosen.includes(c.name)) ?? null;
  const add = (name: string) => {
    const n = name.trim();
    if (!n || chosen.includes(n)) return;
    p.onChange([...chosen, n]);
    setAdding("");
  };
  return (
    // A read node shows its columns as a full-row checklist once the table's columns are known; this
    // stand-in takes the same row before then, so the setting does not change width when they arrive.
    <Field spec={p.spec} id={p.id} problems={p.problems} group wide={p.context.nodeType === "read"}>
      {(describedBy, invalid) => (
        <>
          {/* The box first, under the name as in every other setting; what is chosen under it. */}
          {!p.readOnly && (
            <div className="cv-add-row">
              {rest && rest.length > 0 ? (
                <select
                  id={addId}
                  value=""
                  aria-label={`Add a column to ${p.spec.label}`}
                  aria-describedby={describedBy}
                  aria-invalid={invalid || undefined}
                  onChange={(e) => add(e.target.value)}
                >
                  <option value="">Add a column…</option>
                  {rest.map((c) => (
                    <option key={c.name} value={c.name}>
                      {c.name} ({c.type})
                    </option>
                  ))}
                </select>
              ) : (
                <>
                  <input
                    id={addId}
                    className="mono"
                    value={adding}
                    aria-label={`Column to add to ${p.spec.label}`}
                    aria-describedby={describedBy}
                    spellCheck={false}
                    onChange={(e) => setAdding(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter") {
                        e.preventDefault();
                        add(adding);
                      }
                    }}
                  />
                  <button type="button" onClick={() => add(adding)} disabled={!adding.trim()}>
                    Add
                  </button>
                </>
              )}
            </div>
          )}
          {chosen.length > 0 ? (
            <ul className="cv-chosen" id={p.id} aria-describedby={describedBy}>
              {chosen.map((c, i) => (
                <li key={`${c}-${i}`} className="cv-chip-row">
                  <span className="mono">{c}</span>
                  {!p.readOnly && (
                    <button
                      type="button"
                      className="cv-mini"
                      aria-label={`Remove ${c} from ${p.spec.label}`}
                      onClick={() => p.onChange(chosen.filter((_, j) => j !== i))}
                    >
                      Remove
                    </button>
                  )}
                </li>
              ))}
            </ul>
          ) : (
            <p className="small muted" id={p.id}>
              None chosen.
            </p>
          )}
        </>
      )}
    </Field>
  );
}

/** One column a sort orders by, as its `by` setting writes it: `amount desc nulls first`. */
export type OrderTerm = { column: string; descending: boolean; nullsFirst: boolean };

const ORDER_TERM = /^(.*?)(?:\s+(asc|desc))?(?:\s+nulls\s+(first|last))?$/is;

/** Reads one entry of a sort's `by` as the sort node reads it. */
export function parseOrderTerm(entry: string): OrderTerm {
  const m = ORDER_TERM.exec(entry.trim());
  const column = m?.[1]?.trim() || entry.trim();
  return { column, descending: m?.[2]?.toLowerCase() === "desc", nullsFirst: m?.[3]?.toLowerCase() === "first" };
}

/** Writes one entry of a sort's `by`, leaving out what is the default (ascending, nulls last). */
export function orderTermText(t: OrderTerm): string {
  return t.column + (t.descending ? " desc" : "") + (t.nullsFirst ? " nulls first" : "");
}

/**
 * The columns a sort orders by, first to last: each with its direction and where its empty values
 * go, which can be moved up or removed; new ones are added from the arriving columns.
 */
function OrderControl(p: ControlProps) {
  const terms = asList(p.value).map(parseOrderTerm);
  const cols = p.context.columns.main;
  const [adding, setAdding] = useState("");
  const addId = `${p.id}-add`;
  const rest = cols?.filter((c) => !terms.some((t) => t.column === c.name)) ?? null;
  const write = (next: OrderTerm[]) => p.onChange(next.length ? next.map(orderTermText) : undefined);
  const add = (name: string) => {
    const n = name.trim();
    if (!n || terms.some((t) => t.column === n)) return;
    write([...terms, { column: n, descending: false, nullsFirst: false }]);
    setAdding("");
  };
  const change = (i: number, t: Partial<OrderTerm>) => write(terms.map((x, j) => (j === i ? { ...x, ...t } : x)));
  return (
    <Field spec={p.spec} id={p.id} problems={p.problems} group wide>
      {(describedBy, invalid) => (
        <>
          {!p.readOnly && (
            <div className="cv-add-row">
              {rest && rest.length > 0 ? (
                <select
                  id={addId}
                  value=""
                  aria-label={`Add a column to ${p.spec.label}`}
                  aria-describedby={describedBy}
                  aria-invalid={invalid || undefined}
                  onChange={(e) => add(e.target.value)}
                >
                  <option value="">Add a column…</option>
                  {rest.map((c) => (
                    <option key={c.name} value={c.name}>
                      {c.name} ({c.type})
                    </option>
                  ))}
                </select>
              ) : (
                <>
                  <input
                    id={addId}
                    className="mono"
                    value={adding}
                    aria-label={`Column to add to ${p.spec.label}`}
                    aria-describedby={describedBy}
                    spellCheck={false}
                    onChange={(e) => setAdding(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter") {
                        e.preventDefault();
                        add(adding);
                      }
                    }}
                  />
                  <button type="button" onClick={() => add(adding)} disabled={!adding.trim()}>
                    Add
                  </button>
                </>
              )}
            </div>
          )}
          {terms.length > 0 ? (
            <ol className="cv-order" id={p.id} aria-describedby={describedBy}>
              {terms.map((t, i) => (
                <li key={`${t.column}-${i}`} className="cv-order-row">
                  <span className="mono cv-order-column">{t.column}</span>
                  <select
                    value={t.descending ? "desc" : "asc"}
                    disabled={p.readOnly}
                    aria-label={`Direction for ${t.column}`}
                    onChange={(e) => change(i, { descending: e.target.value === "desc" })}
                  >
                    <option value="asc">Smallest first</option>
                    <option value="desc">Largest first</option>
                  </select>
                  <select
                    value={t.nullsFirst ? "first" : "last"}
                    disabled={p.readOnly}
                    aria-label={`Empty values in ${t.column}`}
                    onChange={(e) => change(i, { nullsFirst: e.target.value === "first" })}
                  >
                    <option value="last">Empty last</option>
                    <option value="first">Empty first</option>
                  </select>
                  {!p.readOnly && i > 0 && (
                    <button
                      type="button"
                      className="cv-mini"
                      aria-label={`Move ${t.column} up`}
                      onClick={() => write(terms.map((x, j) => (j === i - 1 ? terms[i] : j === i ? terms[i - 1] : x)))}
                    >
                      Up
                    </button>
                  )}
                  {!p.readOnly && (
                    <button
                      type="button"
                      className="cv-mini"
                      aria-label={`Remove ${t.column} from ${p.spec.label}`}
                      onClick={() => write(terms.filter((_, j) => j !== i))}
                    >
                      Remove
                    </button>
                  )}
                </li>
              ))}
            </ol>
          ) : (
            <p className="small muted" id={p.id}>
              None chosen.
            </p>
          )}
        </>
      )}
    </Field>
  );
}

type PairKind = "column-map" | "expressions" | "conditions";

function pairLabels(kind: PairKind, nodeType: string | null, key: string): [string, string, string] {
  if (kind === "conditions") return ["Port", "Condition", "Add a route"];
  if (kind === "expressions") {
    if (key === "cast") return ["Column", "Type", "Add a column"];
    return ["Column", nodeType === "aggregate" ? "Summary" : nodeType === "window" ? "Function" : "Expression", "Add a column"];
  }
  if (nodeType === "lookup") return ["Main column", "Lookup column", "Add a key"];
  if (nodeType === "join") return ["Left column", "Right column", "Add a key"];
  if (key === "rename") return ["Column", "New name", "Add a rename"];
  return ["From", "To", "Add a pair"];
}

/**
 * Name → value pairs, in order: a column map (rename, lookup keys), expressions (compute,
 * aggregate, cast) or conditions (route). Rows are kept as typed; the setting is written from the
 * rows with a name, so a half-typed row never loses the others.
 */
function PairsControl(p: ControlProps & { kind: PairKind }) {
  const outside = asPairs(p.value);
  const key = JSON.stringify(outside);
  const [rows, setRows] = useState<[string, string][]>(outside);
  const mine = useRef(key);
  useEffect(() => {
    if (key !== mine.current) {
      setRows(JSON.parse(key) as [string, string][]);
      mine.current = key;
    }
  }, [key]);
  const [left, right, addLabel] = pairLabels(p.kind, p.context.nodeType, p.spec.key);
  const valueIsColumn = p.kind === "column-map" && (p.context.nodeType === "lookup" || p.context.nodeType === "join");
  const leftIsColumn = p.kind === "column-map" || (p.kind === "expressions" && p.spec.key === "cast");
  const dupes = new Set(rows.map((r) => r[0]).filter((n, i, all) => n !== "" && all.indexOf(n) !== i));
  const commit = (next: [string, string][]) => {
    setRows(next);
    const obj: Record<string, string> = {};
    for (const [k, v] of next) if (k.trim() !== "") obj[k.trim()] = v;
    const written = JSON.stringify(Object.entries(obj));
    mine.current = written;
    p.onChange(Object.keys(obj).length ? obj : undefined);
  };
  const problems = dupes.size ? [`Each ${left.toLowerCase()} appears once: ${[...dupes].join(", ")} is repeated.`, ...p.problems] : p.problems;
  const exprRefs = useRef<(HTMLTextAreaElement | null)[]>([]);
  const lastFocused = useRef<number>(-1);
  return (
    <Field spec={p.spec} id={p.id} problems={problems} group wide>
      {(describedBy, invalid) => (
        <>
          {rows.length === 0 && (
            <p className="small muted" id={p.id}>
              None yet.
            </p>
          )}
          <ol className="cv-pairs" id={rows.length ? p.id : undefined}>
            {rows.map(([k, v], i) => {
              const rowId = `${p.id}-${i}`;
              return (
                <li key={i} className="cv-pair">
                  <div className="cv-pair-left">
                    <label className="cv-sub" htmlFor={`${rowId}-k`}>
                      {left} {i + 1}
                    </label>
                    {leftIsColumn ? (
                      <ColumnInput
                        id={`${rowId}-k`}
                        value={k}
                        columns={p.context.columns.main}
                        readOnly={p.readOnly}
                        describedBy={describedBy}
                        invalid={invalid}
                        onChange={(x) => commit(rows.map((r, j) => (j === i ? [x, r[1]] : r)))}
                      />
                    ) : (
                      <input
                        id={`${rowId}-k`}
                        className="mono"
                        value={k}
                        readOnly={p.readOnly}
                        spellCheck={false}
                        autoComplete="off"
                        aria-describedby={describedBy}
                        aria-invalid={invalid || undefined}
                        onChange={(e) => commit(rows.map((r, j) => (j === i ? [e.target.value, r[1]] : r)))}
                      />
                    )}
                  </div>
                  <div className="cv-pair-right">
                    <label className="cv-sub" htmlFor={`${rowId}-v`}>
                      {right} {i + 1}
                    </label>
                    {p.kind === "column-map" ? (
                      <ColumnInput
                        id={`${rowId}-v`}
                        value={v}
                        columns={valueIsColumn ? p.context.columns.lookup : p.context.columns.main}
                        free={!valueIsColumn}
                        readOnly={p.readOnly}
                        describedBy={describedBy}
                        invalid={invalid}
                        onChange={(x) => commit(rows.map((r, j) => (j === i ? [r[0], x] : r)))}
                      />
                    ) : (
                      <textarea
                        id={`${rowId}-v`}
                        ref={(el) => {
                          exprRefs.current[i] = el;
                        }}
                        className="mono cv-expression"
                        rows={1}
                        value={v}
                        readOnly={p.readOnly}
                        spellCheck={false}
                        aria-describedby={describedBy}
                        aria-invalid={invalid || undefined}
                        onFocus={() => {
                          lastFocused.current = i;
                        }}
                        onChange={(e) => commit(rows.map((r, j) => (j === i ? [r[0], e.target.value] : r)))}
                      />
                    )}
                  </div>
                  {!p.readOnly && (
                    <button
                      type="button"
                      className="cv-mini cv-pair-remove"
                      aria-label={`Remove ${left.toLowerCase()} ${k || i + 1}`}
                      onClick={() => commit(rows.filter((_, j) => j !== i))}
                    >
                      Remove
                    </button>
                  )}
                </li>
              );
            })}
          </ol>
          {!p.readOnly && (
            <div className="cv-add-row">
              <button
                type="button"
                onClick={() => {
                  setRows([...rows, ["", ""]]);
                  requestAnimationFrame(() => document.getElementById(`${p.id}-${rows.length}-k`)?.focus());
                }}
              >
                {addLabel}
              </button>
            </div>
          )}
          {!p.readOnly && p.kind !== "column-map" && p.spec.key !== "cast" && rows.length > 0 && (
            <ColumnButtons
              columns={p.context.columns.main}
              label={`Insert a column into the last ${right.toLowerCase()} you were in`}
              onPick={(name) => {
                const i = lastFocused.current >= 0 && lastFocused.current < rows.length ? lastFocused.current : rows.length - 1;
                const el = exprRefs.current[i];
                const { text, caret } = insertAt(el, rows[i][1], quoteColumn(name));
                commit(rows.map((r, j) => (j === i ? [r[0], text] : r)));
                requestAnimationFrame(() => {
                  el?.focus();
                  el?.setSelectionRange(caret, caret);
                });
              }}
            />
          )}
        </>
      )}
    </Field>
  );
}

/** The checks a gate's rule can be, in the order the type list shows them. */
export const RULE_TYPES: { type: string; label: string; column: boolean }[] = [
  { type: "not_null", label: "Has a value (not null)", column: true },
  { type: "unique", label: "No repeats (unique)", column: true },
  { type: "allowed_values", label: "One of a list of values", column: true },
  { type: "matches_pattern", label: "Matches a pattern", column: true },
  { type: "in_range", label: "Between a minimum and maximum", column: true },
  { type: "freshness", label: "Latest date is recent (freshness)", column: true },
  { type: "row_count", label: "Number of rows", column: false },
  { type: "row_count_change", label: "Change in rows since the last scan", column: false },
];

/** A rule's own settings: the YAML key, its label, and whether it is a number or a list of text. */
const RULE_PARAMS: Record<string, { key: string; label: string; as: "text" | "number" | "list" | "boolean" }[]> = {
  not_null: [{ key: "blank_is_missing", label: "Blank text counts as missing", as: "boolean" }],
  unique: [],
  allowed_values: [{ key: "values", label: "Allowed values, separated by commas", as: "list" }],
  matches_pattern: [{ key: "regex", label: "Pattern (regular expression)", as: "text" }],
  in_range: [
    { key: "min", label: "Minimum", as: "text" },
    { key: "max", label: "Maximum", as: "text" },
  ],
  freshness: [{ key: "max_age_hours", label: "Latest value at most this many hours old", as: "number" }],
  row_count: [
    { key: "min", label: "At least this many rows", as: "number" },
    { key: "max", label: "At most this many rows", as: "number" },
  ],
  row_count_change: [{ key: "max_change", label: "Largest change, as a share (0.2 for 20%)", as: "number" }],
};

type Rule = { [key: string]: Value };

const NUMERIC = /^-?(0|[1-9]\d*)(\.\d+)?$/;

/** A typed value as the rule keeps it: a number when it reads as one, else the text; empty removes it. */
function ruleValue(as: "text" | "number" | "list" | "boolean", typed: string | boolean): Value | undefined {
  if (as === "boolean") return typed === true ? true : undefined;
  const t = String(typed);
  if (t.trim() === "") return undefined;
  if (as === "list") return t.split(",").map((v) => v.trim()).filter(Boolean);
  if (as === "number" || NUMERIC.test(t.trim())) return NUMERIC.test(t.trim()) ? Number(t.trim()) : t;
  return t;
}

/**
 * A text box of a rule that keeps exactly what is typed while it has the focus, and writes the
 * value (a number when it reads as one) when it loses it or Enter is pressed: typing 0.05 key by key
 * never passes through 0 or 0. on its way.
 */
function RuleInput({
  id,
  value,
  as,
  onCommit,
  readOnly,
  className,
  placeholder,
  describedBy,
  inputMode,
}: {
  id: string;
  value: Value | undefined;
  as: "text" | "number" | "list";
  onCommit: (v: Value | undefined) => void;
  readOnly: boolean;
  className?: string;
  placeholder?: string;
  describedBy?: string;
  inputMode?: "decimal";
}) {
  const outside = ruleText(value);
  const { draft, setDraft, focusProps } = useDraft(outside);
  const commit = () => {
    const next = ruleValue(as, draft);
    if (JSON.stringify(next ?? null) !== JSON.stringify(value ?? null)) onCommit(next);
  };
  return (
    <input
      id={id}
      className={className}
      inputMode={inputMode}
      placeholder={placeholder}
      value={draft}
      readOnly={readOnly}
      spellCheck={false}
      autoComplete="off"
      aria-describedby={describedBy}
      onFocus={focusProps.onFocus}
      onChange={(e) => setDraft(e.target.value)}
      onBlur={() => {
        commit();
        focusProps.onBlur();
      }}
      onKeyDown={(e) => {
        if (e.key === "Enter") {
          e.preventDefault();
          commit();
        }
      }}
    />
  );
}

const ruleText = (v: Value | undefined) => (v == null ? "" : Array.isArray(v) ? v.join(", ") : typeof v === "object" ? "" : String(v));

/**
 * A gate's rules, one block each: its check, its column, the check's own settings, an optional
 * name and how many rows may fail. Settings the canvas does not show (warn_above, unit, dimension,
 * a unique key of several columns) are kept as they are in the YAML.
 */
function RulesControl(p: ControlProps) {
  const outside = Array.isArray(p.value) ? (p.value.filter((r) => r && typeof r === "object" && !Array.isArray(r)) as Rule[]) : [];
  const key = JSON.stringify(outside);
  const [rows, setRows] = useState<Rule[]>(outside);
  const mine = useRef(key);
  useEffect(() => {
    if (key !== mine.current) {
      setRows(JSON.parse(key) as Rule[]);
      mine.current = key;
    }
  }, [key]);
  const commit = (next: Rule[]) => {
    setRows(next);
    const written = next.filter((r) => typeof r.type === "string" && r.type !== "");
    mine.current = JSON.stringify(written);
    p.onChange(written.length ? written : undefined);
  };
  const set = (i: number, k: string, v: Value | undefined) =>
    commit(
      rows.map((r, j) => {
        if (j !== i) return r;
        const out: Rule = { ...r };
        if (v === undefined) delete out[k];
        else out[k] = v;
        return out;
      }),
    );
  const changeType = (i: number, type: string) =>
    commit(
      rows.map((r, j) => {
        if (j !== i) return r;
        // Keep the name, column and limits; the old check's own settings go.
        const keep: Rule = { type };
        for (const k of ["name", "field", "fields", "fail_above", "warn_above", "unit", "dimension"]) if (r[k] != null) keep[k] = r[k];
        if (!RULE_TYPES.find((t) => t.type === type)?.column) delete keep.field;
        return keep;
      }),
    );
  return (
    <Field spec={p.spec} id={p.id} problems={p.problems} group wide>
      {(describedBy, invalid) => (
        <>
          {rows.length === 0 && (
            <p className="small muted" id={p.id}>
              No rules written here.
            </p>
          )}
          <ol className="cv-pairs cv-rules" id={rows.length ? p.id : undefined}>
            {rows.map((r, i) => {
              const rowId = `${p.id}-${i}`;
              const type = typeof r.type === "string" ? r.type : "";
              const known = RULE_TYPES.find((t) => t.type === type);
              return (
                <li key={i} className="cv-pair cv-rule">
                  <div>
                    <label className="cv-sub" htmlFor={`${rowId}-type`}>
                      Rule {i + 1}: check
                    </label>
                    <select
                      id={`${rowId}-type`}
                      value={type}
                      disabled={p.readOnly}
                      aria-describedby={describedBy}
                      aria-invalid={invalid || undefined}
                      onChange={(e) => changeType(i, e.target.value)}
                    >
                      <option value="">Choose a check</option>
                      {!known && type !== "" && <option value={type}>{type}</option>}
                      {RULE_TYPES.map((t) => (
                        <option key={t.type} value={t.type}>
                          {t.label}
                        </option>
                      ))}
                    </select>
                  </div>
                  {(known?.column ?? true) && (
                    <div>
                      <label className="cv-sub" htmlFor={`${rowId}-field`}>
                        Rule {i + 1}: column
                      </label>
                      <ColumnInput
                        id={`${rowId}-field`}
                        value={ruleText(r.field)}
                        columns={p.context.columns.main}
                        readOnly={p.readOnly}
                        describedBy={describedBy}
                        invalid={invalid}
                        onChange={(x) => set(i, "field", x === "" ? undefined : x)}
                      />
                    </div>
                  )}
                  {(RULE_PARAMS[type] ?? []).map((param) => {
                    const id = `${rowId}-${param.key}`;
                    if (param.as === "boolean") {
                      return (
                        <label key={param.key} className="cv-check" htmlFor={id}>
                          <input
                            id={id}
                            type="checkbox"
                            checked={r[param.key] === true}
                            disabled={p.readOnly}
                            onChange={(e) => set(i, param.key, ruleValue("boolean", e.target.checked))}
                          />{" "}
                          {param.label}
                        </label>
                      );
                    }
                    return (
                      <div key={param.key}>
                        <label className="cv-sub" htmlFor={id}>
                          Rule {i + 1}: {param.label}
                        </label>
                        <RuleInput
                          id={id}
                          as={param.as}
                          className={param.as === "number" ? "num" : "mono"}
                          inputMode={param.as === "number" ? "decimal" : undefined}
                          value={r[param.key]}
                          readOnly={p.readOnly}
                          describedBy={describedBy}
                          onCommit={(v) => set(i, param.key, v)}
                        />
                      </div>
                    );
                  })}
                  <div className="cv-rule-pair">
                    <div>
                      <label className="cv-sub" htmlFor={`${rowId}-name`}>
                        Rule {i + 1}: name (optional)
                      </label>
                      <RuleInput
                        id={`${rowId}-name`}
                        as="text"
                        value={r.name}
                        readOnly={p.readOnly}
                        onCommit={(v) => set(i, "name", v === undefined ? undefined : String(v))}
                      />
                    </div>
                    <div>
                      <label className="cv-sub" htmlFor={`${rowId}-fail`}>
                        Rule {i + 1}: may fail on at most
                      </label>
                      <RuleInput
                        id={`${rowId}-fail`}
                        as="number"
                        className="num"
                        inputMode="decimal"
                        placeholder="0"
                        value={r.fail_above}
                        readOnly={p.readOnly}
                        describedBy={`${rowId}-fail-help`}
                        onCommit={(v) => set(i, "fail_above", v)}
                      />
                      <span id={`${rowId}-fail-help`} className="cv-sub">
                        {r.unit === "count" ? "rows" : "a share of rows, 0.05 for 5%"}
                      </span>
                    </div>
                  </div>
                  {!p.readOnly && (
                    <button
                      type="button"
                      className="cv-mini cv-pair-remove"
                      aria-label={`Remove rule ${i + 1}${r.name ? ` (${ruleText(r.name)})` : ""}`}
                      onClick={() => {
                        const left = rows.length - 1;
                        commit(rows.filter((_, j) => j !== i));
                        // The focus goes to the rule now in this place, or the one before, or Add a rule.
                        window.setTimeout(() => {
                          const next = left === 0 ? null : document.getElementById(`${p.id}-${Math.min(i, left - 1)}-type`);
                          (next ?? document.getElementById(`${p.id}-add`))?.focus();
                        }, 0);
                      }}
                    >
                      Remove
                    </button>
                  )}
                </li>
              );
            })}
          </ol>
          {!p.readOnly && (
            <div className="cv-add-row">
              <button
                type="button"
                id={`${p.id}-add`}
                onClick={() => {
                  setRows([...rows, { type: "" }]);
                  requestAnimationFrame(() => document.getElementById(`${p.id}-${rows.length}-type`)?.focus());
                }}
              >
                Add a rule
              </button>
            </div>
          )}
        </>
      )}
    </Field>
  );
}

/** The control for one setting, chosen by its kind (SettingSpec.Kind). */
export function SettingControl(p: ControlProps) {
  switch (p.spec.kind) {
    case "boolean":
      return <BooleanControl {...p} />;
    case "number":
      return <NumberControl {...p} />;
    case "choice":
      return <ChoiceControl {...p} />;
    case "source":
      return <SourceControl {...p} />;
    case "asset":
      return <AssetControl {...p} />;
    case "expression":
      return <ExpressionControl {...p} />;
    case "expressions":
      return <PairsControl {...p} kind="expressions" />;
    case "conditions":
      return <PairsControl {...p} kind="conditions" />;
    case "column_map":
      return <PairsControl {...p} kind="column-map" />;
    case "column":
      return <ColumnControl {...p} />;
    case "columns":
      return p.context.nodeType === "read" && p.context.columns.main?.length ? (
        <ColumnChecklist {...p} columns={p.context.columns.main} />
      ) : (
        <ColumnsControl {...p} />
      );
    case "order":
      return <OrderControl {...p} />;
    case "rules":
      return <RulesControl {...p} />;
    default:
      return p.context.nodeType === "read" && p.spec.key === "query" ? <SqlControl {...p} /> : <TextControl {...p} />;
  }
}

/** A stable id base for a node's controls. */
export const useControlIds = () => useId().replace(/:/g, "");

import { useEffect, useRef, useState, type FormEvent } from "react";
import { docHref } from "../../docs/links";
import { MAX_PARAMETERS, PARAMETER_KINDS, type DeclaredParameter, type GraphNode, type ParameterSpec } from "./model";
import { defaultWords, draftOf, emptyDraft, namesInValue, parameterProblems, specOf, type DraftField, type ParameterDraft } from "./parameters";

/** The nodes whose settings put in `${name}`, by id. */
export function usedBy(name: string, nodes: GraphNode[]): string[] {
  return nodes.filter((n) => Object.values(n.settings).some((v) => namesInValue(v).includes(name))).map((n) => n.id);
}

const FIELD_LABELS: Record<DraftField, string> = { name: "Name", kind: "Type", defaultText: "Default", description: "Description" };

/**
 * The pipeline's parameters, in the canvas's Settings when no node is selected: a table of each
 * one's name, type, default, description and the nodes that put it in. Designers and admins add,
 * change and remove them there, checked as the server checks `parameters:`; each change is written
 * to the YAML as a person would write it (only that parameter's line changes) and can be undone.
 * Everyone else reads the table.
 */
export function ParametersPanel({
  idBase,
  declared,
  nodes,
  readOnly,
  readOnlyWhy = "Designers and admins change them.",
  onSave,
  onRemove,
}: {
  idBase: string;
  declared: DeclaredParameter[];
  nodes: GraphNode[];
  readOnly: boolean;
  /** Why they cannot be changed here, when read-only. */
  readOnlyWhy?: string;
  /** Writes a new parameter (`was` null) or a changed one; returns why it could not, or null. */
  onSave: (was: string | null, spec: ParameterSpec) => string | null;
  /** Removes one; returns why it could not, or null. */
  onRemove: (name: string) => string | null;
}) {
  // The parameter being changed (its name), "" for a new one, or null when the form is closed.
  const [editing, setEditing] = useState<string | null>(null);
  const [draft, setDraft] = useState<ParameterDraft>(emptyDraft);
  const [problems, setProblems] = useState<Partial<Record<DraftField, string>>>({});
  const [refused, setRefused] = useState<string | null>(null);
  const [said, setSaid] = useState("");
  const firstField = useRef<HTMLInputElement>(null);
  const returnTo = useRef<string | null>(null);
  const id = (k: string) => `${idBase}-${k}`;

  useEffect(() => {
    if (editing != null) firstField.current?.focus();
  }, [editing]);
  // After the form closes, the focus goes back to the button that opened it (or Add, when that row went).
  useEffect(() => {
    if (editing != null || returnTo.current == null) return;
    const el = document.getElementById(returnTo.current) ?? document.getElementById(id("add"));
    returnTo.current = null;
    el?.focus();
  });

  const open = (p: DeclaredParameter | null, from: string) => {
    returnTo.current = from;
    setDraft(p ? draftOf(p) : emptyDraft());
    setProblems({});
    setRefused(null);
    setEditing(p ? p.name : "");
  };
  const close = () => setEditing(null);

  const save = (e: FormEvent) => {
    e.preventDefault();
    if (editing == null) return;
    const was = editing === "" ? null : editing;
    const others = declared.map((p) => p.name).filter((n) => n !== was);
    const before = declared.find((p) => p.name === was) ?? null;
    const found = parameterProblems(draft, others, before);
    setProblems(found);
    if (Object.keys(found).length > 0) {
      setRefused(null);
      document.getElementById(id(Object.keys(found)[0]))?.focus();
      return;
    }
    const spec = specOf(draft, before);
    const why = onSave(was, spec);
    if (why) {
      setRefused(why);
      return;
    }
    setSaid(was == null ? `Parameter ${spec.name} added.` : `Parameter ${spec.name} changed.`);
    if (was != null && spec.name !== was) returnTo.current = id(`edit-${spec.name}`);
    close();
  };

  const remove = (name: string) => {
    const why = onRemove(name);
    if (why) {
      setSaid(why);
      return;
    }
    const n = usedBy(name, nodes);
    setSaid(
      n.length
        ? `Parameter ${name} removed. ${n.join(", ")} still ${n.length === 1 ? "puts" : "put"} in \${${name}}: change ${n.length === 1 ? "it" : "them"} too, or undo.`
        : `Parameter ${name} removed.`,
    );
    returnTo.current = id("add");
    if (editing === name) close();
  };

  const field = (k: DraftField) => ({
    id: id(k),
    "aria-invalid": problems[k] ? true : undefined,
    "aria-describedby": `${id(k)}-help${problems[k] ? ` ${id(k)}-problem` : ""}`,
  });
  const problem = (k: DraftField) =>
    problems[k] ? (
      <p id={`${id(k)}-problem`} className="cv-field-problems">
        {problems[k]}
      </p>
    ) : null;

  return (
    <section className="cv-section cv-params" aria-labelledby={id("h")}>
      <h3 id={id("h")}>Parameters</h3>
      <p className="hint">
        Values a run can be given, each with a default the pipeline runs with when none is given. A node puts one in as{" "}
        <span className="mono">{"${name}"}</span>; they are written in the YAML under <span className="mono">parameters:</span>.{" "}
        {readOnly ? `${readOnlyWhy} ` : ""}
        <a href={docHref("pipelines", "parameters-and-server-variables")}>How parameters work</a>.
      </p>
      {declared.length === 0 ? (
        <p className="small">This pipeline declares no parameters.</p>
      ) : (
        <div className="table-scroll" role="region" aria-labelledby={id("h")} tabIndex={0}>
          <table className="small cv-params-table">
            <thead>
              <tr>
                <th scope="col">Name</th>
                <th scope="col">Type</th>
                <th scope="col">Default</th>
                <th scope="col">Description</th>
                <th scope="col">Put in by</th>
                {!readOnly && (
                  <th scope="col">
                    <span className="visually-hidden">Change</span>
                  </th>
                )}
              </tr>
            </thead>
            <tbody>
              {declared.map((p) => {
                const users = usedBy(p.name, nodes);
                return (
                  <tr key={p.name}>
                    <th scope="row" className="mono">
                      {p.name}
                    </th>
                    <td>{p.kind ?? <span className="pl-bad">{p.typeText ? `${p.typeText} (not a type Kilnmark knows)` : "No type"}</span>}</td>
                    <td className={p.defaultVariable ? undefined : "mono"}>{defaultWords(p)}</td>
                    <td>{p.description ?? <span className="muted">None</span>}</td>
                    <td>{users.length ? <span className="mono">{users.join(", ")}</span> : <span className="muted">No node yet</span>}</td>
                    {!readOnly && (
                      <td className="cv-params-actions">
                        <button type="button" className="cv-mini" id={id(`edit-${p.name}`)} aria-label={`Change ${p.name}`} onClick={() => open(p, id(`edit-${p.name}`))}>
                          Change
                        </button>
                        <button type="button" className="cv-mini cv-danger" aria-label={`Remove ${p.name}`} onClick={() => remove(p.name)}>
                          Remove
                        </button>
                      </td>
                    )}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      <p role="status" className="small notice">
        {said}
      </p>
      {!readOnly && editing == null && (
        <div className="buttons">
          <button type="button" id={id("add")} onClick={() => open(null, id("add"))} disabled={declared.length >= MAX_PARAMETERS}>
            Add a parameter
          </button>
        </div>
      )}
      {!readOnly && editing != null && (
        <form className="cv-params-form" onSubmit={save} noValidate aria-labelledby={id("form-h")}>
          <h4 id={id("form-h")}>{editing === "" ? "New parameter" : `Change ${editing}`}</h4>
          <div className="cv-field">
            <label className="cv-label" htmlFor={id("name")}>
              {FIELD_LABELS.name}
            </label>
            <input
              ref={firstField}
              className="mono"
              autoComplete="off"
              spellCheck={false}
              value={draft.name}
              onChange={(e) => setDraft((d) => ({ ...d, name: e.target.value }))}
              {...field("name")}
            />
            {problem("name")}
            <p className="hint" id={`${id("name")}-help`}>
              A small letter, then small letters, digits and _. Nodes put it in as <span className="mono">{`\${${draft.name.trim() || "name"}}`}</span>
              {editing ? "; renaming it does not change the nodes that put in the old name" : ""}.
            </p>
          </div>
          <div className="cv-field">
            <label className="cv-label" htmlFor={id("kind")}>
              {FIELD_LABELS.kind}
            </label>
            <select
              value={draft.kind}
              onChange={(e) => setDraft((d) => ({ ...d, kind: e.target.value }))}
              {...field("kind")}
            >
              {!(PARAMETER_KINDS as readonly string[]).includes(draft.kind) && (
                <option value={draft.kind}>{draft.kind ? `${draft.kind} (not a type Kilnmark knows)` : "Choose…"}</option>
              )}
              {PARAMETER_KINDS.map((k) => (
                <option key={k} value={k}>
                  {k}
                </option>
              ))}
            </select>
            {problem("kind")}
            <p className="hint" id={`${id("kind")}-help`}>
              A list holds several values, such as the statuses to keep.
            </p>
          </div>
          <div className="cv-field">
            <label className="cv-label" htmlFor={id("defaultText")}>
              {FIELD_LABELS.defaultText}
            </label>
            <input
              className="mono"
              autoComplete="off"
              spellCheck={false}
              value={draft.defaultText}
              onChange={(e) => setDraft((d) => ({ ...d, defaultText: e.target.value }))}
              {...field("defaultText")}
            />
            {problem("defaultText")}
            <p className="hint" id={`${id("defaultText")}-help`}>
              What a run gets when it is given no value: {draft.kind.endsWith("list") ? "values separated by commas" : draft.kind === "boolean" ? "true or false" : "a value of its type"}, or a
              server variable as <span className="mono">{"${vars.name}"}</span>.
            </p>
          </div>
          <div className="cv-field">
            <label className="cv-label" htmlFor={id("description")}>
              {FIELD_LABELS.description} (optional)
            </label>
            <input value={draft.description} onChange={(e) => setDraft((d) => ({ ...d, description: e.target.value }))} {...field("description")} />
            {problem("description")}
            <p className="hint" id={`${id("description")}-help`}>
              What it is for, shown beside <span className="mono">{"${name}"}</span> in the settings and when a run asks for values.
            </p>
          </div>
          {refused && (
            <p role="alert" className="error">
              {refused}
            </p>
          )}
          <div className="buttons">
            <button type="submit" className="primary">
              {editing === "" ? "Add" : "Save the change"}
            </button>
            <button type="button" onClick={close}>
              Cancel
            </button>
          </div>
        </form>
      )}
    </section>
  );
}

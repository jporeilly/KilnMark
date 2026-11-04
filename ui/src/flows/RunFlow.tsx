import { useRef, useState, type FormEvent } from "react";
import { flowsApi, type FlowParameter, type FlowRun, type FlowRunStatus, type FlowNodeStatus } from "../api";
import { defaultText, defaultWords, runValues } from "./parameters";

const LABEL: Record<FlowRunStatus | FlowNodeStatus, string> = {
  pending: "Not started",
  queued: "Queued",
  running: "Running",
  succeeded: "Succeeded",
  failed: "Failed",
  cancelled: "Cancelled",
  skipped: "Skipped",
};

const CLASS: Record<FlowRunStatus | FlowNodeStatus, string> = {
  pending: "badge-none",
  queued: "badge-queued",
  running: "badge-running",
  succeeded: "badge-succeeded",
  failed: "badge-failed",
  cancelled: "badge-none",
  skipped: "badge-none",
};

/** A flow run's or node's status in words, as the chips say it. */
export const flowStatusWords = (status: FlowRunStatus | FlowNodeStatus) => LABEL[status];

/** A flow run's or node's status in words (never colour alone); "Stopping" while a cancel is on its way. */
export function FlowStatus({ status, stopping }: { status: FlowRunStatus | FlowNodeStatus | null; stopping?: boolean }) {
  if (!status) return <span className="badge badge-none">Never run</span>;
  const going = status === "queued" || status === "running";
  return <span className={`badge ${CLASS[status]}`}>{stopping && going ? "Stopping" : LABEL[status]}</span>;
}

export function FlowRunStatus({ run }: { run: FlowRun | null }) {
  return <FlowStatus status={run?.status ?? null} stopping={run?.cancelRequested} />;
}

/**
 * Run now for a flow: runs at once when the flow declares no parameters; otherwise opens a dialog
 * with a field for each, filled with its default, and runs with what was changed. `parameters`
 * are the latest version's when the caller has them; otherwise they are read when it is pressed.
 */
export function RunFlowButton({
  name,
  label,
  parameters,
  disabled,
  title,
  primary,
  onQueued,
  onError,
}: {
  name: string;
  label: string;
  parameters?: FlowParameter[];
  disabled?: boolean;
  title?: string;
  primary?: boolean;
  onQueued: (run: FlowRun) => void;
  onError: (message: string) => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [params, setParams] = useState<FlowParameter[]>([]);
  const [typed, setTyped] = useState<Record<string, string>>({});
  const [problems, setProblems] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [dialogError, setDialogError] = useState<string | null>(null);

  const run = async (values: Record<string, unknown>) => {
    setBusy(true);
    try {
      const r = await flowsApi.run(name, values);
      dialog.current?.close();
      onQueued(r);
    } catch (e) {
      if (dialog.current?.open) setDialogError((e as Error).message);
      else onError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const press = async () => {
    setBusy(true);
    let ps = parameters;
    try {
      if (!ps) ps = (await flowsApi.get(name)).latest?.parameters ?? [];
    } catch (e) {
      setBusy(false);
      onError((e as Error).message);
      return;
    }
    setBusy(false);
    if (ps.length === 0) {
      await run({});
      return;
    }
    setParams(ps);
    setTyped(Object.fromEntries(ps.map((p) => [p.name, defaultText(p)])));
    setProblems({});
    setDialogError(null);
    dialog.current?.showModal();
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const { values, problems: found } = runValues(params, typed);
    setProblems(found);
    if (Object.keys(found).length > 0) return;
    void run(values);
  };

  const fieldId = (p: string) => `run-${name}-${p}`;

  return (
    <>
      <button
        type="button"
        className={primary ? "primary" : undefined}
        onClick={press}
        disabled={disabled || busy}
        aria-label={label}
        title={title}
      >
        {busy && !dialog.current?.open ? "Queueing…" : "Run now"}
      </button>
      <dialog ref={dialog} aria-labelledby={`run-${name}-h`} className="dialog">
        <form onSubmit={submit} noValidate>
          <h2 id={`run-${name}-h`}>Run {name}</h2>
          <p className="hint">
            Values for this run. A field left as its default, or empty, runs with the default; lists are separated by
            commas.
          </p>
          {params.map((p) => (
            <div className="field" key={p.name}>
              <label htmlFor={fieldId(p.name)}>
                <span className="mono">{p.name}</span> ({p.type}
                {p.required ? ", needed" : ""})
              </label>
              {p.type === "boolean" ? (
                <select
                  id={fieldId(p.name)}
                  value={typed[p.name] ?? ""}
                  onChange={(e) => setTyped((t) => ({ ...t, [p.name]: e.target.value }))}
                  aria-invalid={problems[p.name] ? true : undefined}
                  aria-describedby={`${fieldId(p.name)}-hint`}
                >
                  {(p.required || p.defaultVariable) && (
                    <option value="">{p.defaultVariable ? `The server variable ${p.defaultVariable}` : "Choose…"}</option>
                  )}
                  <option value="true">true</option>
                  <option value="false">false</option>
                </select>
              ) : (
                <input
                  id={fieldId(p.name)}
                  value={typed[p.name] ?? ""}
                  inputMode={p.type === "number" ? "decimal" : undefined}
                  autoComplete="off"
                  spellCheck={false}
                  onChange={(e) => setTyped((t) => ({ ...t, [p.name]: e.target.value }))}
                  aria-invalid={problems[p.name] ? true : undefined}
                  aria-describedby={`${fieldId(p.name)}-hint`}
                />
              )}
              <p id={`${fieldId(p.name)}-hint`} className={problems[p.name] ? "hint pl-bad" : "hint"}>
                {problems[p.name] ?? [p.description, defaultWords(p)].filter(Boolean).join(" ")}
              </p>
            </div>
          ))}
          {dialogError && (
            <p role="alert" className="error">
              {dialogError}
            </p>
          )}
          <div className="buttons">
            <button type="submit" className="primary" disabled={busy}>
              {busy ? "Queueing…" : "Run"}
            </button>
            <button type="button" onClick={() => dialog.current?.close()}>
              Cancel
            </button>
          </div>
        </form>
      </dialog>
    </>
  );
}

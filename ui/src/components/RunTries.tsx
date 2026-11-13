import { useEffect, useState } from "react";
import {
  allows,
  flowsApi,
  formatClock,
  formatTime,
  pipelinesApi,
  type AccessRole,
  type Owners,
  type RunTries as Tries,
  type RunTry,
} from "../api";
import { isToken, Who } from "./Who";

/** "14:05": when a run's next try starts, in the reader's time. */
export function clock(iso: string): string {
  return formatClock(iso);
}

/**
 * What a run waiting for its next try says (ADR 0053): "Retrying: try 2 of 4, next at 14:05". Null for a
 * run that is not waiting for one.
 */
export function retrying(run: Tries & { status: string }): string | null {
  if (run.status !== "queued" || !run.retryAt || !run.tryNumber || !run.tries) return null;
  return `Retrying: try ${run.tryNumber} of ${run.tries}, next at ${clock(run.retryAt)}`;
}

/** "Succeeded on try 3" for a run that needed more than one; null otherwise. */
export function triedWords(run: Tries & { status: string }): string | null {
  if (!run.tryNumber || run.tryNumber <= 1 || run.status === "queued" || run.status === "running") return null;
  const how = run.status === "succeeded" ? "Succeeded" : run.status === "failed" ? "Failed" : "Ended";
  return `${how} on try ${run.tryNumber}`;
}

const ENDED: Record<RunTry["status"], string> = {
  succeeded: "Succeeded",
  failed: "Failed",
  cancelled: "Cancelled",
  lost: "Lost with its worker",
};

/**
 * Whether a try's message already says what came next. A run's last try carries the run's error,
 * which ends with why it was not tried again ("…, after 2 tries. Not tried again: all 2 tries were
 * made"), and the same words as what came next: they are shown once.
 */
export function saysNext(t: Pick<RunTry, "message" | "next">): boolean {
  return !!t.next && !!t.message && t.message.includes(t.next);
}

/**
 * Each ended try of a run, in order, with how it ended and what came next (ADR 0053). Nothing for a run
 * that has had one try and is still on it.
 */
export function RunTriesList({ tries, run }: { tries: RunTry[] | undefined; run: Tries & { status: string } }) {
  const waiting = retrying(run);
  if ((!tries || tries.length === 0) && !waiting) return null;
  return (
    <section aria-labelledby="run-tries-h" className="panel">
      <h2 id="run-tries-h">Tries</h2>
      {waiting && (
        <p role="status" className="notice">
          {waiting}
        </p>
      )}
      <ol className="small run-tries">
        {(tries ?? []).map((t) => (
          <li key={t.number}>
            <strong>
              Try {t.number}: {ENDED[t.status]}
            </strong>{" "}
            <span className="muted">
              {formatTime(t.startedAt)} to {formatTime(t.finishedAt)}
            </span>
            {t.message && <div>{t.message}</div>}
            {t.next && !saysNext(t) && (
              <div className="muted">
                {t.next}
                {t.askedBy ? ` (asked by ${t.askedBy})` : ""}
              </div>
            )}
            {saysNext(t) && t.askedBy && <div className="muted">Asked by {t.askedBy}</div>}
          </li>
        ))}
      </ol>
    </section>
  );
}

/**
 * Who answers for a pipeline or flow (ADR 0053): told when its runs fail after their last try, recover
 * or take long. Designers and admins change them; owning gives no rights.
 */
export function OwnersSection({ kind, name, role }: { kind: "pipeline" | "flow"; name: string; role: AccessRole }) {
  const api = kind === "pipeline" ? pipelinesApi : flowsApi;
  const designer = allows(role, "designer");
  const [owners, setOwners] = useState<Owners | null>(null);
  const [editing, setEditing] = useState(false);
  const [text, setText] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let live = true;
    api
      .owners(name)
      .then((o) => live && setOwners(o))
      .catch((e: Error) => live && setError(e.message));
    return () => {
      live = false;
    };
  }, [api, name]);

  const start = () => {
    setText((owners?.owners ?? []).map((o) => o.entry ?? "").filter(Boolean).join("\n"));
    setEditing(true);
    setError(null);
  };

  const submit = async () => {
    setSaving(true);
    try {
      const list = text
        .split(/[\n,]/)
        .map((s) => s.trim())
        .filter(Boolean);
      setOwners(await api.setOwners(name, list));
      setEditing(false);
      setError(null);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <section aria-labelledby="owners-h" className="panel owners">
      <h2 id="owners-h">Owners</h2>
      {owners == null && !error && <p role="status">Loading…</p>}
      {owners && !editing && (
        <>
          {owners.none ? (
            <p className="small warning">{owners.note ?? "No owner: admins are told in their place."}</p>
          ) : (
            <ul className="owners-list" aria-label={`Owners of the ${kind}`}>
              {owners.owners.map((o, i) => (
                <li key={i}>
                  {/* A token by its name and a provider sign-in by its sso: name, the full record on hover. */}
                  {o.kind === "team" ? <span className="km-who">{o.label}</span> : <Who name={o.label} />}
                  {/* A team address hidden from this reader is labelled "A team address" already. */}
                  {(o.kind !== "team" || o.entry != null) && (
                    <span className="small muted">{o.kind === "team" ? "Team address" : isToken(o.label) ? "API token" : "Person"}</span>
                  )}
                </li>
              ))}
            </ul>
          )}
          <p className="hint">
            Owners are told when a run fails after its last try, recovers, or takes longer than expected. Owning gives
            no rights.
          </p>
          {designer && (
            <div className="buttons">
              <button type="button" onClick={start}>
                Change owners
              </button>
            </div>
          )}
        </>
      )}
      {editing && (
        // As the app's other forms: the label above, the box across the card, the hint under it, then the buttons.
        <div className="owners-form">
          <div className="field">
            <label htmlFor="owners-edit">Owners, one a line</label>
            <textarea
              id="owners-edit"
              rows={4}
              value={text}
              spellCheck={false}
              aria-describedby="owners-edit-hint"
              onChange={(e) => setText(e.target.value)}
            />
            <p className="hint" id="owners-edit-hint">
              A person's sign-in name as Kilnmark records it (an API token's name, or sso: and a name for someone who signs
              in through the identity provider), or a team address an admin added under Settings, Email. At most 10.
            </p>
          </div>
          <div className="buttons">
            <button type="button" className="primary" onClick={submit} disabled={saving}>
              {saving ? "Saving…" : "Save owners"}
            </button>
            <button type="button" onClick={() => setEditing(false)} disabled={saving}>
              Cancel
            </button>
          </div>
        </div>
      )}
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
    </section>
  );
}

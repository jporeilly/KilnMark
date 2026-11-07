import { useEffect, useRef, useState } from "react";
import {
  formatTime,
  starterPackApi,
  type StarterPackDetails,
  type StarterPackResult,
  type StarterPackStep,
} from "../api";
import { href } from "../router";
import { useTitle } from "../components/useTitle";
import { Card, DataTable, PageHeader, StatusChip, type Tone } from "../components/ui";
import { STARTER_PACK_CHANGED, StarterPackNotice } from "../shell/StarterPackBanner";

/** What the admin types to turn the pack off. */
export const CONFIRM = "DELETE";

/** The steps, in words, as the server names them. */
const STEP_LABELS: Record<string, string> = {
  database: "Its database",
  "database source": "Database source",
  "files source": "Files source",
  "sample tables": "Sample tables",
  samples: "Samples in the Library",
  "sample flows": "Sample flows",
  "sample pipelines": "Sample pipelines",
  sources: "Sources",
  "server variable": "Server variable",
  "sample files": "Sample files",
};

const OUTCOME: Record<StarterPackStep["outcome"], { label: string; tone: Tone }> = {
  done: { label: "Done", tone: "good" },
  skipped: { label: "Skipped", tone: "neutral" },
  failed: { label: "Failed", tone: "bad" },
};

/** A step's name in words. */
export function stepLabel(step: string): string {
  return STEP_LABELS[step] ?? step;
}


/**
 * Settings › Starter Pack, for admins (ADR 0051): example data and the samples ready to run, for
 * trials, demos and training. Kilnmark does the work itself; this page turns it on, shows each step
 * and how it went, and turns it off again after the admin types DELETE. Only on a Starter Pack
 * install: a production one says so and offers nothing. The example people are the control script's: this page shows its commands and which
 * example accounts have signed in, and never a password.
 */
export function StarterPackPage() {
  useTitle("Starter Pack");
  const [details, setDetails] = useState<StarterPackDetails | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<"on" | "off" | null>(null);
  const [result, setResult] = useState<StarterPackResult | null>(null);
  const [typed, setTyped] = useState("");
  const dialog = useRef<HTMLDialogElement>(null);
  const cancel = useRef<HTMLButtonElement>(null);

  const load = () =>
    starterPackApi
      .details()
      .then(setDetails)
      .catch((e: Error) => setError(e.message));

  useEffect(() => {
    load();
  }, []);

  // While the sample tables are being created, follow their run.
  const running = details?.tablesRun && ["queued", "running"].includes(details.tablesRun.status);
  useEffect(() => {
    if (!running) return;
    const t = window.setInterval(load, 3000);
    return () => window.clearInterval(t);
  }, [running]);

  const turnOn = async () => {
    setBusy("on");
    setError(null);
    setResult(null);
    try {
      setResult(await starterPackApi.turnOn());
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
      window.dispatchEvent(new Event(STARTER_PACK_CHANGED));
      load();
    }
  };

  const turnOff = async () => {
    setBusy("off");
    setError(null);
    setResult(null);
    try {
      setResult(await starterPackApi.turnOff(typed));
      dialog.current?.close();
    } catch (e) {
      setError((e as Error).message);
      dialog.current?.close();
    } finally {
      setBusy(null);
      setTyped("");
      window.dispatchEvent(new Event(STARTER_PACK_CHANGED));
      load();
    }
  };

  const d = details;
  const production = d?.mode === "production";
  const blocked = production ? "production" : d && !d.ready ? "not-ready" : null;

  return (
    <>
      <PageHeader
        title="Starter Pack"
        crumbs={[{ label: "Settings", href: href.settings() }, { label: "Starter Pack" }]}
        lead="Example data and the samples ready to run, for training, demonstrations and development, on a Starter Pack install only. Kilnmark adds it all itself, in a database and a folder of its own, and takes it all out again when you turn it off."
        actions={
          d &&
          !production &&
          (d.on ? (
            <button
              type="button"
              className="danger"
              disabled={busy !== null}
              onClick={() => {
                setTyped("");
                dialog.current?.showModal();
                cancel.current?.focus();
              }}
            >
              Turn off…
            </button>
          ) : (
            <button type="button" className="primary" onClick={turnOn} disabled={busy !== null || blocked !== null}>
              {busy === "on" ? "Turning on…" : "Turn on"}
            </button>
          ))
        }
      />
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {!d && !error && (
        <p role="status" className="muted">
          Loading…
        </p>
      )}
      {production && (
        <p className="hint" role="note">
          This is a production install: the Starter Pack, for training and development only, is never turned on here,
          and it has no example people. The templates are here as on any install. For a trial, install a separate
          Kilnmark in Starter Pack mode: an install never moves from one mode to the other.
        </p>
      )}
      {d && !production && !d.ready && (
        <p className="warning" role="note">
          The Starter Pack cannot be turned on here yet. {d.notReady}.
        </p>
      )}
      {d && !production && (
        <Card title="What the pack holds" titleId="starter-what" className="km-section">
          <p>
            {d.on ? (
              <>
                <StatusChip tone="warn" dot>
                  On
                </StatusChip>{" "}
                since {d.since ? formatTime(d.since) : "a while"}
                {d.by ? `, turned on by ${d.by}` : ""}. Everyone sees this marking on every page:
              </>
            ) : (
              <>
                <StatusChip tone="neutral">Off</StatusChip> Turned on, it adds:
              </>
            )}
          </p>
          <StarterPackNotice on={d.on} />
          <ul>
            <li>
              the sample tables, in <span className="mono">{d.database}</span>: a database of its own on Kilnmark&apos;s
              PostgreSQL server, closed to all but a login of its own, which reads nothing of Kilnmark&apos;s;
            </li>
            <li>
              a database source and a files source on it, both allowing writing, with the files in{" "}
              <span className="mono">{d.files ?? "the files folder"}</span>;
            </li>
            <li>every sample, in the Library, ready to run ({d.samples} there now).</li>
          </ul>
          {d.tablesRun && (
            <p>
              Sample tables:{" "}
              <a href={href.pipelineRun(d.tablesRun.id)}>
                run {d.tablesRun.id}, {d.tablesRun.status}
              </a>
              .
            </p>
          )}
          <p className="hint">
            Turning it off removes every sample in the Library, the pack&apos;s sources, its tables (the database stays,
            empty) and its files, and nothing else. Both are in the audit log.
          </p>
        </Card>
      )}
      {d && !production && (
        <Card title="Example people" titleId="starter-people" className="km-section">
          <p>
            Five people at kilnmark.com addresses, one per role, for trying the roles out: vera.lane (viewer),
            sam.okafor (steward), dana.reyes (designer), priya.shah (designer, sees sensitive values) and
            alex.morgan (admin). The person who runs Kilnmark makes them with the control script, with the bundled
            Keycloak and <span className="mono">KILNMARK_STARTER=allowed</span> in config/.env, typing their
            password, which is never shown. Kilnmark holds no Keycloak credential, and turning the pack off here does
            not remove them; what the command did is in the audit log.
          </p>
          <dl className="km-starter-commands">
            <dt>To add them</dt>
            <dd className="mono">{d.people.add}</dd>
            <dt>To remove them</dt>
            <dd className="mono">{d.people.remove}</dd>
          </dl>
          {d.people.signedIn.length ? (
            <p className="warning" role="note">
              Example accounts have signed in here: {d.people.signedIn.join(", ")}. They stay in Keycloak until removed
              with the command above.
            </p>
          ) : (
            <p className="hint">No example account has signed in here.</p>
          )}
        </Card>
      )}
      {result && <Steps result={result} />}
      <dialog ref={dialog} aria-labelledby="off-h" aria-describedby="off-what" className="dialog">
        <h2 id="off-h">Turn the Starter Pack off?</h2>
        <div id="off-what">
          <p>This removes, for good:</p>
          <ul>
            <li>every sample pipeline and flow in the Library, with their runs and schedules;</li>
            <li>the pack&apos;s two sources, and Kilnmark&apos;s sample data source unless your own pipelines or flows read it;</li>
            <li>the tables in kilnmark_samples (the database stays, empty) and the pack&apos;s files.</li>
          </ul>
          <label>
            Type {CONFIRM} to go on
            <input
              type="text"
              value={typed}
              onChange={(e) => setTyped(e.target.value)}
              autoComplete="off"
              spellCheck={false}
            />
          </label>
        </div>
        <div className="buttons">
          <button type="button" className="danger" onClick={turnOff} disabled={typed !== CONFIRM || busy !== null}>
            {busy === "off" ? "Turning off…" : "Turn off and remove"}
          </button>
          <button type="button" ref={cancel} onClick={() => dialog.current?.close()}>
            Cancel
          </button>
        </div>
      </dialog>
    </>
  );
}

/** Each step of the last turn on or off, and how it went. */
export function Steps({ result }: { result: StarterPackResult }) {
  return (
    <Card title={result.on ? "Turned on" : "Turned off"} titleId="starter-steps" className="km-section">
      <DataTable label="Each step and how it went">
        <thead>
          <tr>
            <th scope="col">Step</th>
            <th scope="col">How it went</th>
            <th scope="col">What happened</th>
          </tr>
        </thead>
        <tbody>
          {result.steps.map((s) => (
            <tr key={s.step}>
              <td>{stepLabel(s.step)}</td>
              <td>
                <StatusChip tone={OUTCOME[s.outcome].tone}>{OUTCOME[s.outcome].label}</StatusChip>
              </td>
              <td>{s.detail}</td>
            </tr>
          ))}
        </tbody>
      </DataTable>
    </Card>
  );
}

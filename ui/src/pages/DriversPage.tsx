import { useEffect, useId, useState } from "react";
import { api, formatTime, type DriverItem } from "../api";
import { href } from "../router";
import { useTitle } from "../components/useTitle";
import { Card, EmptyState, PageHeader, StatusChip, type Tone } from "../components/ui";

const STATE: Record<string, { tone: Tone; label: string }> = {
  ready: { tone: "good", label: "Installed" },
  missing: { tone: "warn", label: "Not installed" },
  mismatch: { tone: "bad", label: "Wrong file" },
  unreadable: { tone: "bad", label: "Cannot be read" },
};

/**
 * Settings › Drivers, for admins: the drivers Kilnmark cannot ship, which connectors need them,
 * and installing each: accept its licence and the server downloads that exact version and checks
 * it, or, on a server without internet access, the file to put in the drivers folder.
 */
export function DriversPage() {
  useTitle("Drivers");
  const [items, setItems] = useState<DriverItem[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    api
      .drivers()
      .then(setItems)
      .catch((e: Error) => setError(e.message));
  }, []);
  const replace = (d: DriverItem) => setItems((list) => (list ?? []).map((x) => (x.id === d.id ? d : x)));
  return (
    <>
      <PageHeader
        title="Drivers"
        crumbs={[{ label: "Settings", href: href.settings() }, { label: "Drivers" }]}
        lead="Some databases' drivers cannot ship with Kilnmark because of their licences. Install them here: accept the licence for your organisation and the server downloads the exact version Kilnmark was tested with, and checks it."
      />
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {items === null && !error && <p role="status" className="muted">Loading…</p>}
      {items?.length === 0 && (
        <EmptyState title="Nothing to install">Every connector on this server has what it needs.</EmptyState>
      )}
      {items?.map((d) => <DriverCard key={d.id} driver={d} onChange={replace} />)}
    </>
  );
}

function DriverCard({ driver: d, onChange }: { driver: DriverItem; onChange: (d: DriverItem) => void }) {
  const id = useId();
  const [agreed, setAgreed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const state = STATE[d.state] ?? { tone: "neutral" as Tone, label: d.state };

  const accept = async () => {
    setBusy(true);
    setProblem(null);
    try {
      onChange(await api.acceptDriver(d.id));
    } catch (e) {
      setProblem((e as Error).message);
      api.drivers().then((all) => all.filter((x) => x.id === d.id).forEach(onChange)).catch(() => undefined);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card
      title={`${d.coordinates} ${d.version}`}
      titleId={`${id}-title`}
      className="km-section"
      actions={<StatusChip tone={state.tone} dot>{state.label}</StatusChip>}
    >
      <p>
        Needed by {d.usedBy.join(", ")}. Licence:{" "}
        <a href={d.licenceUrl} target="_blank" rel="noopener noreferrer">
          {d.licence}
          <span className="visually-hidden"> (opens in a new tab)</span>
        </a>
        .
      </p>
      {d.acceptedBy && (
        <p className="hint">
          Licence accepted by {d.acceptedBy} on {formatTime(d.acceptedAt)}.
        </p>
      )}
      {d.state !== "ready" && d.message && <p className="km-callout km-callout--info">{d.message}</p>}
      {d.state !== "ready" && d.download && (
        <div className="form">
          <label>
            <input type="checkbox" checked={agreed} onChange={(e) => setAgreed(e.target.checked)} />
            {" "}I have read the licence and accept it on my organisation's behalf
          </label>
          <div className="buttons">
            <button type="button" className="primary" onClick={() => agreed && !busy && accept()}
              aria-disabled={!agreed || busy}>
              {busy ? "Downloading…" : "Accept and download"}
            </button>
          </div>
        </div>
      )}
      {d.state !== "ready" && !d.download && (
        <div>
          <p>This server does not download drivers. To install this one:</p>
          <ol>
            <li>
              Read and accept the licence for your organisation, then download{" "}
              <a href={d.downloadUrl} rel="noopener noreferrer"><span className="mono">{d.fileName}</span></a> on a
              machine with internet access.
            </li>
            <li>
              Put it, unchanged, in the drivers folder <span className="mono">{d.folder}</span> on the server.
            </li>
            <li>Reload this page: it should say Installed. No restart is needed.</li>
          </ol>
          <p className="hint">
            Its SHA-256 must be <span className="mono">{d.sha256}</span>; any other file is not used.
          </p>
        </div>
      )}
      {problem && (
        <p role="alert" className="error">
          {problem}
        </p>
      )}
      {d.state === "ready" && (
        <p className="hint">
          <span className="mono">{d.fileName}</span> is in <span className="mono">{d.folder}</span> and checked.
        </p>
      )}
    </Card>
  );
}

import { useEffect, useState } from "react";
import { api, formatTime, type Me, type Source } from "../api";
import { href } from "../router";
import { Status } from "../components/Status";
import { SourceTile } from "../components/ConnectorTile";
import { useTitle } from "../components/useTitle";

export function HomePage(_: { me: Me }) {
  useTitle("");
  const [sources, setSources] = useState<Source[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.sources().then(setSources).catch((e: Error) => setError(e.message));
  }, []);

  return (
    <>
      <h1 tabIndex={-1}>Find and trust your data</h1>
      <p className="lead">
        Search every table, view and column Kilnmark has scanned. Press <kbd>Ctrl</kbd>+<kbd>K</kbd> to search
        from anywhere.
      </p>
      <section aria-labelledby="sources-h" className="panel">
        <h2 id="sources-h">Your sources</h2>
        {error && <p role="alert" className="error">{error}</p>}
        {sources === null && !error && <p role="status">Loading sources…</p>}
        {sources?.length === 0 && (
          <p>
            No sources yet. <a href={href.sources()}>Add a database</a> and scan it to fill the catalogue.
          </p>
        )}
        {sources && sources.length > 0 && (
          <ul className="plain-list">
            {sources.map((s) => (
              <li key={s.name} className="with-tile">
                <SourceTile kind={s.kind} size="sm" />
                <strong>{s.name}</strong> <Status run={s.lastRun} />{" "}
                <span className="muted">
                  {s.lastRun ? `last scan ${formatTime(s.lastRun.finishedAt ?? s.lastRun.requestedAt)}` : ""}
                </span>
              </li>
            ))}
          </ul>
        )}
        <p>
          <a href={href.sources()}>Manage sources</a>
        </p>
      </section>
    </>
  );
}

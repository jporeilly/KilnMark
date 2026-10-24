import { useCallback, useEffect, useRef, useState } from "react";
import { api, type Connector, type ConnectorCatalogue, type Source } from "../api";
import { ConnectionPanel } from "./ConnectionPanel";
import { ConnectorGallery } from "./ConnectorGallery";

/**
 * The gallery, and beside it the connection panel for the card chosen. Closing the panel (its
 * Close button or Escape) puts focus back on that card.
 */
export function AddConnection({ onSaved }: { onSaved: (s: Source, scanQueued: boolean) => void }) {
  const [catalogue, setCatalogue] = useState<ConnectorCatalogue | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [chosen, setChosen] = useState<Connector | null>(null);
  const card = useRef<HTMLButtonElement | null>(null);

  useEffect(() => {
    api.catalogue().then(setCatalogue).catch((e: Error) => setError(e.message));
  }, []);

  const close = useCallback(() => {
    setChosen(null);
    window.setTimeout(() => card.current?.focus(), 0);
  }, []);

  return (
    <section aria-labelledby="add-h" className="panel">
      <h2 id="add-h">Add a connection</h2>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {!catalogue && !error && <p role="status">Loading connectors…</p>}
      {catalogue && (
        <div className={chosen ? "add-connection with-panel" : "add-connection"}>
          <ConnectorGallery
            catalogue={catalogue}
            chosen={chosen?.kind ?? null}
            onChoose={(c, el) => {
              card.current = el;
              setChosen(c);
            }}
          />
          {chosen && (
            <ConnectionPanel
              key={chosen.kind}
              connector={chosen}
              onClose={close}
              onSaved={(s, queued) => {
                onSaved(s, queued);
                close();
              }}
            />
          )}
        </div>
      )}
    </section>
  );
}

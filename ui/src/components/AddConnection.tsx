import { useCallback, useEffect, useRef, useState, type RefObject } from "react";
import { api, type Connector, type ConnectorCatalogue, type Source } from "../api";
import { ConnectionPanel } from "./ConnectionPanel";
import { ConnectorGallery } from "./ConnectorGallery";
import { Icon } from "./icons";

/**
 * The gallery, and beside it the connection panel for the card chosen. Closing the panel (its
 * Close button or Escape) puts focus back on that card.
 */
export function AddConnection({
  onSaved,
  onClose,
  headingRef,
}: {
  onSaved: (s: Source, scanQueued: boolean) => void;
  onClose?: () => void;
  headingRef?: RefObject<HTMLHeadingElement | null>;
}) {
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
    <section aria-labelledby="add-h" className="km-card km-section">
      <div className="km-card-head">
        <h2 id="add-h" className="km-card-title" tabIndex={-1} ref={headingRef}>
          Add a connection
        </h2>
        {onClose && (
          <div className="km-card-actions">
            <button type="button" className="km-icon-button" onClick={onClose} aria-label="Close Add a connection">
              <Icon name="close" size={20} />
            </button>
          </div>
        )}
      </div>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {!catalogue && !error && <p role="status">Loading connectors…</p>}
      {catalogue && (
        <div className="add-connection">
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

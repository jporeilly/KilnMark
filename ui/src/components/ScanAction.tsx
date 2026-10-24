import { useEffect, useRef, useState } from "react";
import { api, type Source } from "../api";

const busy = (s: Source) => s.lastRun?.status === "queued" || s.lastRun?.status === "running";

/**
 * "Scan now", with a check first: the server lists what the source holds, and if a scan would find
 * nothing (or Kilnmark cannot reach the source) the warning is shown, and the scan starts only if
 * the person chooses to go ahead.
 */
export function ScanAction({
  source,
  onQueued,
  onError,
}: {
  source: Source;
  onQueued: (message: string) => void;
  onError: (message: string) => void;
}) {
  const [checking, setChecking] = useState(false);
  const [warnings, setWarnings] = useState<string[] | null>(null);
  const alertRef = useRef<HTMLDivElement>(null);
  const label = source.displayName;

  useEffect(() => {
    if (warnings) alertRef.current?.focus();
  }, [warnings]);

  const queue = async () => {
    setWarnings(null);
    try {
      await api.scan(source.name);
      onQueued(`Scan of ${label} queued. A worker will start it shortly.`);
    } catch (e) {
      onError((e as Error).message);
    }
  };

  const start = async () => {
    setChecking(true);
    try {
      const list = await api.containers(source.name);
      if (list.warnings.length > 0) {
        setWarnings(list.warnings);
      } else {
        await queue();
      }
    } catch (e) {
      setWarnings([`Kilnmark could not check the source before scanning: ${(e as Error).message}`]);
    } finally {
      setChecking(false);
    }
  };

  return (
    <div className="scan-action">
      <button type="button" onClick={start} disabled={busy(source) || checking}>
        {checking ? "Checking…" : source.lastRun?.status === "queued" ? "Queued…" : busy(source) ? "Scanning…" : "Scan now"}
        <span className="visually-hidden"> ({label})</span>
      </button>
      {warnings && (
        <div className="warning" role="alert" tabIndex={-1} ref={alertRef}>
          <p>
            <strong>Before you scan {label}:</strong>
          </p>
          <ul>
            {warnings.map((w) => (
              <li key={w}>{w}</li>
            ))}
          </ul>
          <div className="buttons">
            <button type="button" onClick={queue}>
              Scan anyway
            </button>
            <button type="button" onClick={() => setWarnings(null)}>
              Cancel
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

import { useEffect, useState } from "react";
import type { AssetList, Preview } from "../api";
import { PreviewTable } from "./PreviewTable";

/**
 * The tables or files in one container, loaded when opened, a page at a time, each with a Preview
 * of its first rows.
 */
export function AssetBrowser({
  container,
  load,
  preview,
}: {
  container: string;
  load: (container: string, offset: number) => Promise<AssetList>;
  preview: (path: string, rows: number) => Promise<Preview>;
}) {
  const [list, setList] = useState<AssetList | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [shown, setShown] = useState<Preview | null>(null);
  const [loadingPreview, setLoadingPreview] = useState<string | null>(null);
  const [rows, setRows] = useState(100);

  const page = (offset: number) => {
    setError(null);
    load(container, offset)
      .then(setList)
      .catch((e: Error) => setError(e.message));
  };
  useEffect(() => page(0), [container]); // eslint-disable-line

  const open = async (path: string) => {
    setLoadingPreview(path);
    setError(null);
    try {
      setShown(await preview(path, rows));
    } catch (e) {
      setShown(null);
      setError((e as Error).message);
    } finally {
      setLoadingPreview(null);
    }
  };

  return (
    <div className="asset-browser">
      {!list && !error && <p role="status">Listing…</p>}
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {list && (
        <>
          <div className="field-row">
            <div className="field">
              <label htmlFor={`rows-${container}`}>Rows to preview</label>
              <input
                id={`rows-${container}`}
                type="number"
                min={1}
                max={1000}
                value={rows}
                onChange={(e) => setRows(Math.max(1, Math.min(1000, Number(e.target.value) || 100)))}
              />
            </div>
          </div>
          <ul className="asset-list">
            {list.items.map((a) => (
              <li key={a.path}>
                <span className="mono">{a.name}</span> <span className="muted small">{a.kind}</span>
                {a.readable ? (
                  <button type="button" onClick={() => loadingPreview === null && open(a.path)}
                    aria-disabled={loadingPreview !== null}>
                    {loadingPreview === a.path ? "Reading…" : "Preview"}
                    <span className="visually-hidden"> {a.name}</span>
                  </button>
                ) : (
                  <span className="small muted"> (recorded, not read as rows)</span>
                )}
              </li>
            ))}
          </ul>
          <div className="buttons">
            <span className="small" role="status">
              {list.total === 0
                ? "Nothing here."
                : `${list.offset + 1} to ${list.offset + list.items.length} of ${list.total}`}
            </span>
            {list.offset > 0 && (
              <button type="button" onClick={() => page(Math.max(0, list.offset - 50))}>
                Previous page
              </button>
            )}
            {list.offset + list.items.length < list.total && (
              <button type="button" onClick={() => page(list.offset + list.items.length)}>
                Next page
              </button>
            )}
          </div>
        </>
      )}
      {shown && <PreviewTable preview={shown} />}
    </div>
  );
}

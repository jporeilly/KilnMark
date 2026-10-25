import { useEffect, useRef, useState, type KeyboardEvent } from "react";
import { api, type Asset, type SearchHit } from "../api";

export type AssetDetails = { asset: Asset | null; loading: boolean; error: string | null };

/** The details of one asset, for pickers: its name and fields. Loads again when the URN changes. */
export function useAsset(urn: string): AssetDetails {
  const [state, setState] = useState<{ urn: string; asset: Asset | null; error: string | null }>({
    urn: "",
    asset: null,
    error: null,
  });
  const wanted = urn.trim();
  useEffect(() => {
    if (!wanted) return;
    let live = true;
    api
      .asset(wanted)
      .then((asset) => live && setState({ urn: wanted, asset, error: null }))
      .catch((e: Error) => live && setState({ urn: wanted, asset: null, error: e.message }));
    return () => {
      live = false;
    };
  }, [wanted]);
  if (!wanted) return { asset: null, loading: false, error: null };
  if (state.urn !== wanted) return { asset: null, loading: true, error: null };
  return { asset: state.asset, loading: false, error: state.error };
}

const looksLikeUrn = (text: string) => /^urn:\S+$/.test(text);
const SHOWN = 20;
const LIMIT = 50;

/**
 * Chooses an asset by searching for it, or by pasting its URN. The chosen asset is shown by name,
 * source and path, with its URN. Results are a list of buttons: Tab moves through them, Enter or
 * Space chooses one. With `source`, only assets in that source are offered. With `fixed`, the
 * asset is shown but cannot be changed. `details` is the chosen asset as loaded by the caller
 * (useAsset), so it is fetched once. `invalid` marks the search box, which `errorId` then
 * describes.
 */
export function AssetPicker({
  id,
  legend,
  urn,
  details,
  onChange,
  source,
  fixed,
  fixedHint,
  invalid,
  errorId,
}: {
  id: string;
  legend: string;
  urn: string;
  details: AssetDetails;
  onChange: (urn: string) => void;
  source?: string;
  fixed?: boolean;
  fixedHint?: string;
  invalid?: boolean;
  errorId?: string;
}) {
  const { asset, loading, error } = details;
  const [text, setText] = useState("");
  const [hits, setHits] = useState<SearchHit[] | null>(null);
  const [searching, setSearching] = useState(false);
  const [searchError, setSearchError] = useState<string | null>(null);
  const [said, setSaid] = useState("");
  const input = useRef<HTMLInputElement>(null);
  const query = text.trim();
  const pasted = looksLikeUrn(query);

  useEffect(() => {
    if (!query || pasted) {
      setHits(null);
      setSearchError(null);
      setSearching(false);
      return;
    }
    let live = true;
    setSearching(true);
    const timer = window.setTimeout(() => {
      api
        .findAssets(query, source, LIMIT)
        .then((r) => {
          if (!live) return;
          // A server that does not know the source filter returns every source's assets: narrow them here too.
          setHits(source ? r.hits.filter((h) => h.source === source) : r.hits);
          setSearchError(null);
        })
        .catch((e: Error) => live && setSearchError(e.message))
        .finally(() => live && setSearching(false));
    }, 300);
    return () => {
      live = false;
      window.clearTimeout(timer);
    };
  }, [query, pasted, source]);

  const choose = (to: string, label: string) => {
    onChange(to);
    setText("");
    setHits(null);
    setSaid(`Chosen: ${label}.`);
    input.current?.focus();
  };

  // Enter in the search box must not send the form: it takes a pasted URN, or the only result.
  const onKey = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key !== "Enter") return;
    e.preventDefault();
    if (pasted) choose(query, query);
    else if (hits?.length === 1) choose(hits[0].urn, hits[0].name);
  };

  const status = searchError
    ? ""
    : searching
      ? "Searching…"
      : hits
        ? hits.length === 0
          ? source
            ? `No assets in the source ${source} match. Try part of the name.`
            : "No assets match. Try part of the name, or check the source has been scanned."
          : `${hits.length}${hits.length >= LIMIT ? " or more" : ""} matching ${hits.length === 1 ? "asset" : "assets"}${
              hits.length > SHOWN ? `, the first ${SHOWN} shown. Type more of the name to narrow them down` : ""
            }.`
        : said;

  return (
    <fieldset className="picker">
      <legend>{legend}</legend>
      {urn.trim() ? (
        <div id={`${id}-chosen`}>
          {asset ? (
            <p>
              <strong>{asset.name}</strong>{" "}
              <span className="muted small">
                in {asset.source}
                {asset.container ? ` · ${asset.container}` : ""}
              </span>
            </p>
          ) : loading ? (
            <p className="muted">Loading the asset…</p>
          ) : error ? (
            <p className="error">Not found: {error}</p>
          ) : null}
          <p className="mono small muted">{urn}</p>
        </div>
      ) : (
        <p id={`${id}-chosen`} className="muted">
          None chosen yet.
        </p>
      )}
      {fixed ? (
        fixedHint && <p className="hint">{fixedHint}</p>
      ) : (
        <>
          <div className="field">
            <label htmlFor={`${id}-search`}>{urn.trim() ? "Find another asset" : "Find an asset"}</label>
            <input
              id={`${id}-search`}
              ref={input}
              type="search"
              value={text}
              onChange={(e) => {
                setText(e.target.value);
                setSaid("");
              }}
              onKeyDown={onKey}
              autoComplete="off"
              spellCheck={false}
              aria-invalid={invalid || undefined}
              aria-describedby={`${id}-chosen ${id}-hint ${id}-status${invalid && errorId ? ` ${errorId}` : ""}`}
            />
            <p id={`${id}-hint`} className="hint">
              Type part of a table, view or file name{source ? ` in the source ${source}` : ""}. You can also paste
              the asset's URN, shown at the foot of its page.
            </p>
          </div>
          <p id={`${id}-status`} role="status" className="hint">
            {status}
          </p>
          {searchError && (
            <p role="alert" className="error">
              {searchError}
            </p>
          )}
          {pasted && (
            <p>
              <button type="button" onClick={() => choose(query, query)}>
                Use this URN
              </button>{" "}
              <span className="mono small">{query}</span>
            </p>
          )}
          {hits && hits.length > 0 && (
            <ul className="checklist" aria-label="Matching assets">
              {hits.slice(0, SHOWN).map((h, i) => (
                <li key={h.urn}>
                  <button
                    type="button"
                    className="link-button"
                    onClick={() => choose(h.urn, h.name)}
                    aria-describedby={`${id}-hit-${i}`}
                  >
                    {h.name}
                  </button>
                  <span id={`${id}-hit-${i}`} className="small muted">
                    {h.kind} in {h.source}
                    {h.container ? ` · ${h.container}` : ""}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </>
      )}
    </fieldset>
  );
}

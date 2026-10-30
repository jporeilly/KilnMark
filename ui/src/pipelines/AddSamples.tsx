import { useEffect, useRef, useState } from "react";
import { api, samplesApi, type SampleCatalogue, type SampleInfo, type SampleOutcome, type SamplePlaceholder, type Source } from "../api";
import { href } from "../router";

/** The sources to offer for a placeholder: those of its kind first, then the rest. */
export function sourceGroups(p: SamplePlaceholder, sources: Source[]): { fitting: Source[]; others: Source[] } {
  return {
    fitting: sources.filter((s) => s.category === p.category),
    others: sources.filter((s) => s.category !== p.category),
  };
}

/** A source as an option: its display name and name, and for the files placeholder whether it can be written to. */
export function sourceLabel(p: SamplePlaceholder, s: Source): string {
  const name = s.displayName !== s.name ? `${s.displayName} (${s.name})` : s.name;
  return p.category === "files" && s.settings.allow_writing !== "true" ? `${name}, does not allow writing` : name;
}

/** The sample pipelines that would be added: those whose every placeholder has a source. */
export function addable(samples: SampleInfo[], picks: Record<string, string>): SampleInfo[] {
  return samples.filter((s) => s.type === "pipeline" && s.sources.every((p) => picks[p]));
}

/** The placeholders given a source, as the API takes them; one left unset is left out. */
export function chosenSources(picks: Record<string, string>): Record<string, string> {
  return Object.fromEntries(Object.entries(picks).filter(([, v]) => v));
}

/**
 * Add samples: the small pipelines that ship with Kilnmark, which name their sources by
 * placeholders. The person chooses one of their saved sources for each placeholder; a placeholder
 * left unset skips the samples that use it, and a sample already here is left as it is.
 */
export function AddSamples({ onAdded }: { onAdded: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [catalogue, setCatalogue] = useState<SampleCatalogue | null>(null);
  const [sources, setSources] = useState<Source[]>([]);
  const [picks, setPicks] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [results, setResults] = useState<SampleOutcome[] | null>(null);
  const close = useRef<HTMLButtonElement>(null);
  // Each opening of the dialog: answers to an earlier opening's requests are dropped.
  const opening = useRef(0);

  const open = async () => {
    const mine = ++opening.current;
    setError(null);
    setResults(null);
    setPicks({});
    setCatalogue(null);
    setAdding(false);
    dialog.current?.showModal();
    try {
      const [c, s] = await Promise.all([samplesApi.list(), api.sources()]);
      if (mine !== opening.current) return;
      setCatalogue(c);
      setSources([...s].sort((a, b) => a.name.localeCompare(b.name)));
    } catch (e) {
      if (mine === opening.current) setError((e as Error).message);
    }
  };

  const add = async () => {
    const mine = opening.current;
    setAdding(true);
    setError(null);
    try {
      const r = await samplesApi.add(chosenSources(picks));
      if (mine !== opening.current) return;
      setResults(r.samples.filter((s) => !(s.outcome === "skipped" && s.reason?.startsWith("a flow"))));
    } catch (e) {
      if (mine === opening.current) setError((e as Error).message);
    } finally {
      // Some samples may have been added even when the request failed part way.
      onAdded();
      if (mine === opening.current) setAdding(false);
    }
  };

  // The Add button goes when the results come: keep the focus in the dialog.
  useEffect(() => {
    if (results) close.current?.focus();
  }, [results]);

  const pipelines = catalogue?.samples.filter((s) => s.type === "pipeline") ?? [];
  const flows = catalogue?.samples.filter((s) => s.type === "flow") ?? [];
  const toAdd = addable(pipelines, picks);
  const added = results?.filter((r) => r.outcome === "added") ?? [];

  return (
    <>
      <button type="button" onClick={open}>
        Add samples…
      </button>
      <dialog ref={dialog} aria-labelledby="samples-h" className="dialog pl-samples">
        <h2 id="samples-h">Add the samples</h2>
        {!results && (
          <>
            <p>
              Kilnmark comes with {pipelines.length || "some"} small sample pipelines, one idea each, named{" "}
              <span className="mono">sample-…</span>. They name their sources by placeholders: choose one of your saved
              sources for each. Samples using a placeholder you leave unset are not added, and a sample already here is
              left as it is.
            </p>
            {catalogue === null && !error && <p role="status">Loading…</p>}
            {catalogue?.placeholders.map((p) => {
              const using = pipelines.filter((s) => s.sources.includes(p.name)).length;
              const all = pipelines.length > 0 && using === pipelines.length;
              const { fitting, others } = sourceGroups(p, sources);
              return (
                <div className="field" key={p.name}>
                  <label htmlFor={`sample-src-${p.name}`}>
                    <span className="mono">{p.name}</span>{" "}
                    <span className="muted small">
                      ({all ? "every sample writes here" : `${using} ${using === 1 ? "sample" : "samples"}`})
                    </span>
                  </label>
                  <select
                    id={`sample-src-${p.name}`}
                    value={picks[p.name] ?? ""}
                    aria-describedby={`sample-src-${p.name}-hint`}
                    onChange={(e) => setPicks({ ...picks, [p.name]: e.target.value })}
                  >
                    <option value="">Don't add the samples that use it</option>
                    {fitting.length > 0 && (
                      <optgroup label={p.category === "files" ? "File sources" : "Databases"}>
                        {fitting.map((s) => (
                          <option key={s.name} value={s.name}>
                            {sourceLabel(p, s)}
                          </option>
                        ))}
                      </optgroup>
                    )}
                    {others.length > 0 && (
                      <optgroup label="Other sources">
                        {others.map((s) => (
                          <option key={s.name} value={s.name}>
                            {sourceLabel(p, s)}
                          </option>
                        ))}
                      </optgroup>
                    )}
                  </select>
                  <p id={`sample-src-${p.name}-hint`} className="hint">
                    {p.description}.
                  </p>
                </div>
              );
            })}
            {catalogue && sources.length === 0 && (
              <p>
                You have no saved sources yet. <a href={href.sources()}>Add a source</a> first.
              </p>
            )}
            {flows.length > 0 && (
              <p className="hint">
                The {flows.length} sample flows are not added: flows are added once the flows API comes. They are in
                the release's <span className="mono">samples</span> folder to read.
              </p>
            )}
            <p className="hint">
              Each sample is checked against what Kilnmark has scanned of your sources: a table it reads that the scan
              did not find saves it as a draft with problems. Open it to change what it reads.
            </p>
          </>
        )}
        {results && (
          <div>
            <p role="status">
              {added.length === 0
                ? "No samples were added."
                : `Added ${added.length} ${added.length === 1 ? "sample" : "samples"}.`}
            </p>
            <ul className="pl-sample-results">
              {results.map((r) => (
                <li key={r.name}>
                  {r.outcome === "added" ? <a href={href.pipeline(r.name)}>{r.name}</a> : <span className="mono">{r.name}</span>}
                  {": "}
                  {r.outcome === "added" && (r.valid ? "added" : <span className="pl-bad">added as a draft, with problems</span>)}
                  {r.outcome === "exists" && "already here, left as it is"}
                  {r.outcome === "skipped" && <span className="muted">not added: {r.reason}</span>}
                  {r.outcome === "failed" && <span className="pl-bad">could not be added: {r.reason}</span>}
                </li>
              ))}
            </ul>
          </div>
        )}
        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
        <div className="buttons">
          {!results && (
            <button type="button" className="primary" onClick={add} disabled={adding || toAdd.length === 0}>
              {adding
                ? "Adding…"
                : toAdd.length === 0
                  ? "Add samples"
                  : `Add ${toAdd.length} ${toAdd.length === 1 ? "sample" : "samples"}`}
            </button>
          )}
          <button type="button" ref={close} onClick={() => dialog.current?.close()}>
            {results ? "Close" : "Cancel"}
          </button>
        </div>
      </dialog>
    </>
  );
}

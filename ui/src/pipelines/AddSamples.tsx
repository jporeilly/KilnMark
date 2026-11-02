import { useEffect, useRef, useState } from "react";
import {
  api,
  samplesApi,
  type PipelineRun,
  type SampleCatalogue,
  type SampleInfo,
  type SampleOutcome,
  type SamplePlaceholder,
  type Source,
} from "../api";
import { href } from "../router";
import { RunStatus } from "./RunStatus";

/** The placeholders the sample tables stand in for: both read the one database they are created in. */
export const TABLE_PLACEHOLDERS = ["crm-db", "shop-db"];

/** The schema to suggest for the sample tables in a database: the first it reads, else its kind's usual one. */
export function defaultSchema(s: Source | undefined): string {
  if (!s) return "";
  const listed = (s.settings.schemas ?? "").split(/[;,]/).map((x) => x.trim()).find(Boolean);
  if (listed) return listed;
  return s.kind === "postgresql" ? "public" : s.kind === "sqlserver" ? "dbo" : "";
}

/** The databases the sample tables can be created in: those that allow writing. */
export function tableTargets(sources: Source[]): Source[] {
  return sources.filter((s) => s.category === "databases" && s.settings.allow_writing === "true");
}

/** Where the samples can write their files: file sources that allow writing. */
export function fileTargets(sources: Source[]): Source[] {
  return sources.filter((s) => s.category === "files" && s.settings.allow_writing === "true");
}

/** The source kind of Kilnmark's own sample data: read-only, so never somewhere to write. */
export const SAMPLE_DATA_KIND = "sample-data";

/** The sources to offer for a placeholder: those of its kind first, then the rest; never the sample data. */
export function sourceGroups(p: SamplePlaceholder, sources: Source[]): { fitting: Source[]; others: Source[] } {
  const usable = sources.filter((s) => s.kind !== SAMPLE_DATA_KIND);
  return {
    fitting: usable.filter((s) => s.category === p.category),
    others: usable.filter((s) => s.category !== p.category),
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

const label = (s: Source) => (s.displayName !== s.name ? `${s.displayName} (${s.name})` : s.name);

/**
 * Add samples: the small pipelines that ship with Kilnmark, which name their sources by
 * placeholders. The starter pack does it all at once: it creates the sample tables (Kilnmark's
 * synthetic sample data) in a database and schema the person picks, and adds every sample reading
 * them and writing to a file source they pick. Or they choose one of their saved sources for each
 * placeholder; a placeholder left unset skips the samples that use it. A sample already here is
 * left as it is.
 */
export function AddSamples({ onAdded }: { onAdded: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [catalogue, setCatalogue] = useState<SampleCatalogue | null>(null);
  const [sources, setSources] = useState<Source[]>([]);
  const [picks, setPicks] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [results, setResults] = useState<SampleOutcome[] | null>(null);
  // The sample tables the samples read, when they do: the database and schema they are in.
  const [tables, setTables] = useState<{ source: string; schema: string } | null>(null);
  const [useTables, setUseTables] = useState(false);
  // The starter pack's choices: the database and schema for the tables, the file source to write to.
  const [target, setTarget] = useState("");
  const [schema, setSchema] = useState("");
  const [filesTo, setFilesTo] = useState("");
  const [created, setCreated] = useState<PipelineRun | null>(null);
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
    setTables(null);
    setUseTables(false);
    setCreated(null);
    dialog.current?.showModal();
    try {
      const [c, s] = await Promise.all([samplesApi.list(), api.sources()]);
      if (mine !== opening.current) return;
      const sorted = [...s].sort((a, b) => a.name.localeCompare(b.name));
      setCatalogue(c);
      setSources(sorted);
      // Where the tables were made, if that database can still have them; else the first that can.
      const targets = tableTargets(sorted);
      const was = c.tables && targets.some((x) => x.name === c.tables!.source) ? c.tables : null;
      setTarget(was?.source ?? targets[0]?.name ?? "");
      setSchema(was?.schema ?? defaultSchema(targets[0]));
      setFilesTo(fileTargets(sorted)[0]?.name ?? "");
      if (c.tables && sorted.some((x) => x.name === c.tables!.source)) readTables(c.tables);
    } catch (e) {
      if (mine === opening.current) setError((e as Error).message);
    }
  };

  /** The samples read the sample tables in this database and schema. */
  const readTables = (t: { source: string; schema: string }) => {
    setTables(t);
    setUseTables(true);
    setPicks((p) => ({ ...p, ...Object.fromEntries(TABLE_PLACEHOLDERS.map((n) => [n, t.source])) }));
  };

  /** Adds the samples: with the sources given, reading the sample tables in schema when given. */
  const addWith = async (mine: number, sourcesFor: Record<string, string>, tablesSchema?: string) => {
    try {
      const r = await samplesApi.add(sourcesFor, tablesSchema);
      if (mine !== opening.current) return;
      setResults(r.samples.filter((s) => !(s.outcome === "skipped" && s.reason?.startsWith("a flow"))));
    } finally {
      // Some samples may have been added even when the request failed part way.
      onAdded();
    }
  };

  const add = async () => {
    const mine = opening.current;
    setAdding(true);
    setError(null);
    try {
      await addWith(mine, chosenSources(picks), useTables && tables ? tables.schema : undefined);
    } catch (e) {
      if (mine === opening.current) setError((e as Error).message);
    } finally {
      if (mine === opening.current) setAdding(false);
    }
  };

  /** The starter pack: the sample tables created, then every sample added reading them. */
  const addStarterPack = async () => {
    const mine = opening.current;
    setAdding(true);
    setError(null);
    try {
      // Tables already being created in this opening (adding the samples failed after): not again.
      let made = created && tables ? tables : null;
      if (!made) {
        const r = await samplesApi.createTables(target, schema.trim());
        if (mine !== opening.current) return;
        setCreated(r.run);
        made = { source: r.source, schema: r.schema };
        readTables(made);
      }
      await addWith(mine, { "crm-db": made.source, "shop-db": made.source, files: filesTo }, made.schema);
    } catch (e) {
      if (mine === opening.current) setError((e as Error).message);
    } finally {
      if (mine === opening.current) setAdding(false);
    }
  };

  // The Add buttons go when the results come: keep the focus in the dialog.
  useEffect(() => {
    if (results) close.current?.focus();
  }, [results]);

  const pipelines = catalogue?.samples.filter((s) => s.type === "pipeline") ?? [];
  const flows = catalogue?.samples.filter((s) => s.type === "flow") ?? [];
  const toAdd = addable(pipelines, picks);
  const added = results?.filter((r) => r.outcome === "added") ?? [];
  const databases = tableTargets(sources);
  const fileStores = fileTargets(sources);
  const lastRun = created ?? catalogue?.tables?.lastRun ?? null;

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
              <span className="mono">sample-…</span>, and synthetic sample data for them to read. A sample already here
              is left as it is.
            </p>
            {catalogue === null && !error && <p role="status">Loading…</p>}
            {catalogue && (
              <fieldset className="pl-sample-tables">
                <legend>Starter pack</legend>
                <p className="hint">
                  Everything at once: the sample data created as tables in one schema of a database (crm_customers,
                  shop_orders and so on, by a pipeline called <span className="mono">sample-tables</span>), and every
                  sample added reading them, writing its files to a file source.
                </p>
                {databases.length === 0 || fileStores.length === 0 ? (
                  <p className="hint">
                    The starter pack needs a database and a file source that allow writing
                    {databases.length === 0 && fileStores.length === 0
                      ? ""
                      : databases.length === 0
                        ? " (no database does yet)"
                        : " (no file source does yet)"}
                    : a designer or an admin turns on <strong>Allow writing</strong> under <a href={href.sources()}>Sources</a>. Or
                    choose sources of your own below.
                  </p>
                ) : (
                  <>
                    <div className="field-row">
                      <div className="field">
                        <label htmlFor="sample-tables-db">Database for the tables</label>
                        <select
                          id="sample-tables-db"
                          value={target}
                          onChange={(e) => {
                            setTarget(e.target.value);
                            setSchema(defaultSchema(databases.find((s) => s.name === e.target.value)));
                          }}
                        >
                          {databases.map((s) => (
                            <option key={s.name} value={s.name}>
                              {label(s)}
                            </option>
                          ))}
                        </select>
                      </div>
                      <div className="field">
                        <label htmlFor="sample-tables-schema">Schema</label>
                        <input
                          id="sample-tables-schema"
                          value={schema}
                          onChange={(e) => setSchema(e.target.value)}
                          aria-describedby="sample-tables-schema-hint"
                        />
                        <p id="sample-tables-schema-hint" className="hint">
                          It must exist. Tables of the same names there are replaced.
                        </p>
                      </div>
                      <div className="field">
                        <label htmlFor="sample-files-to">Files go to</label>
                        <select id="sample-files-to" value={filesTo} onChange={(e) => setFilesTo(e.target.value)}>
                          {fileStores.map((s) => (
                            <option key={s.name} value={s.name}>
                              {label(s)}
                            </option>
                          ))}
                        </select>
                      </div>
                    </div>
                    <div className="buttons">
                      <button
                        type="button"
                        className="primary"
                        onClick={addStarterPack}
                        disabled={adding || !target || !schema.trim() || !filesTo}
                      >
                        {adding ? "Adding…" : "Add the starter pack"}
                      </button>
                    </div>
                  </>
                )}
              </fieldset>
            )}
            {catalogue && (
              <fieldset className="pl-sample-tables">
                <legend>Or choose your own sources</legend>
                <p className="hint">
                  The samples name their sources by placeholders: choose one of your saved sources for each. Samples
                  using a placeholder you leave unset are not added.
                </p>
                {tables && (
                  <p>
                    <label>
                      <input
                        type="checkbox"
                        checked={useTables}
                        onChange={(e) => (e.target.checked ? readTables(tables) : setUseTables(false))}
                      />{" "}
                      Read the sample tables in <span className="mono">{tables.source}</span>, schema{" "}
                      <span className="mono">{tables.schema}</span>
                    </label>{" "}
                    {lastRun && (
                      <>
                        <RunStatus run={lastRun} /> <a href={href.pipelineRun(lastRun.id)}>Run {lastRun.id}</a>
                      </>
                    )}
                  </p>
                )}
                {catalogue.placeholders.map((p) => {
                  const fixed = useTables && tables && TABLE_PLACEHOLDERS.includes(p.name);
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
                        disabled={!!fixed}
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
                        {fixed ? `Reads the sample tables in ${tables!.source}, schema ${tables!.schema}.` : `${p.description}.`}
                      </p>
                    </div>
                  );
                })}
                {sources.length === 0 && (
                  <p>
                    You have no saved sources yet. <a href={href.sources()}>Add a source</a> first.
                  </p>
                )}
                {!useTables && (
                  <p className="hint">
                    Each sample is checked against what Kilnmark has scanned of your sources: a table it reads that the
                    scan did not find saves it as a draft with problems. Open it to change what it reads.
                  </p>
                )}
                <div className="buttons">
                  <button type="button" onClick={add} disabled={adding || toAdd.length === 0}>
                    {adding
                      ? "Adding…"
                      : toAdd.length === 0
                        ? "Add samples"
                        : `Add ${toAdd.length} ${toAdd.length === 1 ? "sample" : "samples"}`}
                  </button>
                </div>
              </fieldset>
            )}
            {flows.length > 0 && (
              <p className="hint">
                The {flows.length} sample flows are not added: flows are added once the flows API comes. They are in
                the release's <span className="mono">samples</span> folder to read.
              </p>
            )}
          </>
        )}
        {results && (
          <div>
            <p role="status">
              {added.length === 0
                ? "No samples were added."
                : `Added ${added.length} ${added.length === 1 ? "sample" : "samples"}.`}
              {created && (
                <>
                  {" "}
                  The sample tables are being created: <a href={href.pipelineRun(created.id)}>run {created.id}</a>.
                </>
              )}
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
          <button type="button" ref={close} onClick={() => dialog.current?.close()}>
            {results ? "Close" : "Cancel"}
          </button>
        </div>
      </dialog>
    </>
  );
}

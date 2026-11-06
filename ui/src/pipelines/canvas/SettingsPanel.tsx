import { useEffect, useState } from "react";
import { api, type PipelineColumn, type PipelineProblem, type PipelineNodeQuality, type PipelineValidation, type Source, type NodeType } from "../../api";
import { dimensionWord, percent, RULE_STATUS_WORDS, nodeQualityWords } from "../quality";
import type { PredictedQuality } from "../prediction";
import { PredictedSection } from "./PredictedSection";
import { browseApi } from "../../browse/browseApi";
import { columnsText } from "../outline";
import { arrivingColumns, columnSide, kindWord, problemSetting, problemWords, shownSettings } from "./insight";
import { portOfLink, portsOf, refText, type Catalogue, type GraphEdge, type GraphNode, type Value } from "./model";
import { SettingControl } from "./SettingControls";
import { runWords, NodeIcon, type NodeRunInfo } from "./NodeCard";
import { NodeMetricsList } from "../NodeMetricsList";
import type { NodeMetrics } from "../nodeMetrics";

const assetCache = new Map<string, Promise<string[]>>();

/** Tables and files of a source, by the path a pipeline names them with: for the asset picker. */
function assetsOf(source: string): Promise<string[]> {
  let p = assetCache.get(source);
  if (!p) {
    p = (async () => {
      const prefix = `urn:kilnmark:${source}:`;
      const out: string[] = [];
      const containers = await browseApi.containers(source, "", 0, 20);
      for (const c of containers.items) {
        const assets = await browseApi.assets(source, c.name, "", 0, 100);
        for (const a of assets.items) if (a.urn.startsWith(prefix)) out.push(a.urn.slice(prefix.length));
      }
      return out;
    })().catch(() => []);
    assetCache.set(source, p);
  }
  return p;
}

function useAssets(source: string | null): string[] | null {
  const [state, setState] = useState<{ source: string; assets: string[] } | null>(null);
  useEffect(() => {
    if (!source) return;
    let live = true;
    void assetsOf(source).then((assets) => live && setState({ source, assets }));
    return () => {
      live = false;
    };
  }, [source]);
  return source && state?.source === source ? state.assets : null;
}

/** Kept a minute, so a table scanned again shows its new columns without reloading the page. */
const columnCache = new Map<string, { at: number; columns: Promise<PipelineColumn[] | null> }>();
const COLUMNS_KEPT_MS = 60_000;

/** The columns of a table or file as the catalogue knows them, in order: for a read node's choices. */
function columnsOf(urn: string): Promise<PipelineColumn[] | null> {
  const kept = columnCache.get(urn);
  if (kept && Date.now() - kept.at < COLUMNS_KEPT_MS) return kept.columns;
  const columns = api
    .asset(urn)
    .then((a) => [...a.fields].sort((x, y) => x.position - y.position).map((f) => ({ name: f.name, type: f.kind })))
    .catch(() => {
      columnCache.delete(urn); // not scanned yet, say: try again next time
      return null;
    });
  columnCache.set(urn, { at: Date.now(), columns });
  return columns;
}

function useAssetColumns(urn: string | null): PipelineColumn[] | null {
  const [state, setState] = useState<{ urn: string; columns: PipelineColumn[] | null } | null>(null);
  useEffect(() => {
    if (!urn) return;
    let live = true;
    void columnsOf(urn).then((columns) => live && setState({ urn, columns }));
    return () => {
      live = false;
    };
  }, [urn]);
  return urn && state?.urn === urn ? state.columns : null;
}

/**
 * The selected node's settings, generated from its type's settings schema: one control per
 * setting kind, each with its help, default and the problems validation found in it. Above them,
 * the node's id and what it reads from; below, the columns leaving it and its last run.
 */
export function SettingsPanel({
  node,
  type,
  catalogue,
  check,
  problems,
  sources,
  readOnly,
  run,
  quality = null,
  predicted = null,
  metrics = null,
  parameters = [],
  onSetting,
  onRename,
  onDisconnect,
  onConnectMenu,
  onDelete,
  onDuplicate,
}: {
  node: GraphNode;
  type: NodeType | undefined;
  catalogue: Catalogue;
  check: PipelineValidation | null;
  problems: PipelineProblem[];
  sources: Source[] | null;
  readOnly: boolean;
  run: NodeRunInfo | null;
  /** The node's measured quality in that run, for writes and gates. */
  quality?: PipelineNodeQuality | null;
  /** The node's predicted quality as the pipeline stands, for writes and gates (an estimate). */
  predicted?: PredictedQuality | null;
  /** The node's time, rate, memory and how its time went, from the last run once it has ended. */
  metrics?: NodeMetrics | null;
  /** The parameters the pipeline declares, offered as `${name}` in expressions. */
  parameters?: string[];
  onSetting: (key: string, value: Value | undefined, kind: string) => void;
  onRename: (to: string) => string | null;
  onDisconnect: (edge: GraphEdge) => void;
  onConnectMenu: () => void;
  onDelete: () => void;
  onDuplicate: () => void;
}) {
  const [idDraft, setIdDraft] = useState(node.id);
  const [idProblem, setIdProblem] = useState<string | null>(null);
  useEffect(() => {
    setIdDraft(node.id);
    setIdProblem(null);
  }, [node.id]);

  const sourceName = typeof node.settings.source === "string" ? node.settings.source : null;
  const sourceKind = sources?.find((s) => s.name === sourceName)?.kind ?? null;
  const { specs, writerHidden } = shownSettings(type, node, sourceKind);
  const known = new Set(type?.settings.map((s) => s.key) ?? []);
  const extra = Object.keys(node.settings).filter((k) => !known.has(k));
  const assets = useAssets(sourceName);
  const ports = portsOf(node, catalogue);
  // A read node has nothing arriving: its columns and where name the table's own columns.
  const assetName = typeof node.settings.asset === "string" ? node.settings.asset : null;
  const tableColumns = useAssetColumns(node.type === "read" && sourceName && assetName ? `urn:kilnmark:${sourceName}:${assetName}` : null);
  const columns = {
    main: node.type === "read" ? tableColumns : arrivingColumns(check, node.id, node.type === "lookup" ? "main" : undefined),
    lookup: node.type === "lookup" ? arrivingColumns(check, node.id, "lookup") : null,
  };
  const bySetting = new Map<string, string[]>();
  const general: PipelineProblem[] = [];
  for (const p of problems) {
    const k = problemSetting(p, node);
    if (k && (known.has(k) || k in node.settings)) bySetting.set(k, [...(bySetting.get(k) ?? []), problemWords(p)]);
    else general.push(p);
  }
  const outline = check?.nodes.find((s) => s.id === node.id);
  const notes = check?.notes.filter((n) => n.node === node.id) ?? [];
  const base = `cv-set-${node.id}`;

  const rename = () => {
    const to = idDraft.trim();
    if (to === node.id) return;
    const problem = onRename(to);
    setIdProblem(problem);
  };

  return (
    <div className="cv-panel-body">
      <div className="cv-set-side">
      <div className="cv-panel-head">
        <span className={`cv-kind-chip cv-kind-${type?.kind ?? "unknown"}`}>
          <NodeIcon type={node.type} kind={type?.kind} size={16} />
          {kindWord(type?.kind)} · {type?.label ?? node.type ?? "no type"}
        </span>
        <h3 className="cv-panel-title mono">{node.id}</h3>
        {type?.help && <p className="small muted cv-type-help">{type.help}</p>}
        {!type && node.type && <p className="small pl-bad">This server has no node type called {node.type}.</p>}
      </div>

      <div className="cv-field">
        <label className="cv-label" htmlFor={`${base}-id`}>
          Node id
        </label>
        <input
          id={`${base}-id`}
          className="mono"
          value={idDraft}
          readOnly={readOnly}
          aria-describedby={`${base}-id-help${idProblem ? ` ${base}-id-problem` : ""}`}
          aria-invalid={idProblem ? true : undefined}
          onChange={(e) => setIdDraft(e.target.value)}
          onBlur={rename}
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              e.preventDefault();
              rename();
            }
          }}
        />
        {idProblem && (
          <p id={`${base}-id-problem`} className="cv-field-problems" role="alert">
            {idProblem}
          </p>
        )}
        {/* Under the box, as in every setting: label, box, problems, help. */}
        <p className="hint" id={`${base}-id-help`}>
          Small letters, digits, - and _. Changing it updates the nodes reading from it.
        </p>
      </div>

      {ports.inputs.length > 0 && (
        <section className="cv-section" aria-labelledby={`${base}-reads`}>
          <h4 id={`${base}-reads`}>Reads from</h4>
          <ul className="cv-links">
            {ports.inputs.map((port) => {
              const links = node.inputs.filter((l) => portOfLink(l, ports) === port);
              return (
                <li key={port}>
                  <span className="cv-port-name">{port}</span>
                  {ports.many.has(port) && <span className="small muted"> (any number)</span>}
                  {links.length === 0 ? (
                    <span className="small pl-bad"> Not connected</span>
                  ) : (
                    <ul>
                      {links.map((l) => (
                        <li key={refText(l.from, l.fromPort)} className="cv-link-row">
                          <span className="mono">{refText(l.from, l.fromPort)}</span>
                          {!readOnly && (
                            <button
                              type="button"
                              className="cv-mini"
                              aria-label={`Disconnect ${refText(l.from, l.fromPort)} from ${node.id}.${port}`}
                              onClick={() => onDisconnect({ from: l.from, fromPort: l.fromPort, to: node.id, toPort: port })}
                            >
                              Disconnect
                            </button>
                          )}
                        </li>
                      ))}
                    </ul>
                  )}
                </li>
              );
            })}
          </ul>
        </section>
      )}

      {!readOnly && (
        <div className="buttons cv-panel-actions">
          <button type="button" onClick={onConnectMenu}>
            Connect to…
          </button>
          <button type="button" onClick={onDuplicate}>
            Duplicate
          </button>
          <button type="button" className="cv-danger" onClick={onDelete}>
            Delete node
          </button>
        </div>
      )}

      {general.length > 0 && (
        <ul className="cv-node-problems" aria-label={`Problems with ${node.id}`}>
          {general.map((p, i) => (
            <li key={i}>{problemWords(p)}</li>
          ))}
        </ul>
      )}
      </div>

      {type && (
        <section className="cv-section cv-set-main" aria-labelledby={`${base}-settings`}>
          <h4 id={`${base}-settings`}>Settings</h4>
          {specs.length === 0 && extra.length === 0 && <p className="small muted">This node has no settings.</p>}
          {specs.map((spec) => (
            <SettingControl
              key={`${node.id}:${spec.key}`}
              spec={spec}
              id={`${base}-${spec.key}`}
              value={node.settings[spec.key]}
              text={node.texts[spec.key]}
              readOnly={readOnly}
              problems={bySetting.get(spec.key) ?? []}
              onChange={(v) => onSetting(spec.key, v, spec.kind)}
              context={{
                columns,
                side: columnSide(node.type, spec.key),
                sources,
                writing: type.kind === "sink",
                assets: spec.kind === "asset" ? assets : null,
                nodeType: node.type,
                parameters,
              }}
            />
          ))}
          {notes.length > 0 && (
            <ul className="cv-node-notes" aria-label={`Notes on ${node.id}`}>
              {notes.map((n, i) => (
                <li key={i}>{n.message.replace(`Node ${node.id}: `, "")}</li>
              ))}
            </ul>
          )}
          {writerHidden > 0 && (
            <p className="hint">
              {sourceName
                ? sourceKind
                  ? "Other writer settings belong to other kinds of source."
                  : "The writer settings appear once the source is known."
                : "Choose a source to see the settings its writer takes."}
            </p>
          )}
          {extra.map((k) => (
            <SettingControl
              key={`${node.id}:${k}`}
              spec={{ key: k, label: k, kind: "text", required: false, defaultValue: null, help: "A setting this node type does not list.", choices: [] }}
              id={`${base}-${k}`}
              value={node.settings[k]}
              text={node.texts[k]}
              readOnly={readOnly || typeof node.settings[k] === "object"}
              problems={bySetting.get(k) ?? []}
              onChange={(v) => onSetting(k, v, "text")}
              context={{ columns, side: null, sources, writing: false, assets: null, nodeType: node.type }}
            />
          ))}
        </section>
      )}

      <div className="cv-set-run">
      <section className="cv-section" aria-labelledby={`${base}-cols`}>
        <h4 id={`${base}-cols`}>Columns leaving this node</h4>
        <p className="small">{outline ? columnsText(outline) : "Known once the pipeline has been checked."}</p>
      </section>

      {predicted && <PredictedSection p={predicted} id={`${base}-predicted`} />}

      {run && (
        <section className="cv-section" aria-labelledby={`${base}-run`}>
          <h4 id={`${base}-run`}>Last run</h4>
          <p className="small">{runWords(run)}</p>
          {run.error && <p className="small pl-bad">{run.error}</p>}
          {metrics && <NodeMetricsList m={metrics} label={`Metrics of ${node.id} in the last run`} />}
          {quality && (
            <>
              <p className="small">{nodeQualityWords(quality)}</p>
              {quality.measured && Object.keys(quality.measured.dimensions).length > 0 && (
                <ul className="small cv-quality-dims">
                  {Object.entries(quality.measured.dimensions).map(([d, v]) => (
                    <li key={d}>
                      {dimensionWord(d)}: {percent(v)}
                    </li>
                  ))}
                </ul>
              )}
              {quality.gate && quality.gate.rules.length > 0 && (
                <ul className="small cv-quality-rules" aria-label="The gate's rules">
                  {quality.gate.rules.map((r, i) => (
                    <li key={`${i}-${r.origin}`}>
                      {r.name}: {RULE_STATUS_WORDS[r.status] ?? r.status}
                      {r.status !== "error" && r.total > 0 && !["freshness", "row_count", "row_count_change"].includes(r.type)
                        ? `, ${r.failing.toLocaleString("en-GB")} of ${r.total.toLocaleString("en-GB")} rows failing`
                        : ""}
                      {r.status === "error" && r.message ? ` (${r.message})` : ""}
                    </li>
                  ))}
                </ul>
              )}
              {quality.note && <p className="small muted">{quality.note}</p>}
            </>
          )}
        </section>
      )}
      </div>
    </div>
  );
}

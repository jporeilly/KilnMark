import { useEffect, useState } from "react";
import type { PipelineProblem, PipelineValidation, Source, StepType } from "../../api";
import { browseApi } from "../../browse/browseApi";
import { columnsText } from "../outline";
import { arrivingColumns, columnSide, kindWord, problemSetting, problemWords, shownSettings } from "./insight";
import { portOfLink, portsOf, refText, type Catalogue, type GraphEdge, type GraphStep, type Value } from "./model";
import { SettingControl } from "./SettingControls";
import { runWords, StepIcon, type StepRunInfo } from "./StepNode";

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

/**
 * The selected step's settings, generated from its type's settings schema: one control per
 * setting kind, each with its help, default and the problems validation found in it. Above them,
 * the step's id and what it reads from; below, the columns leaving it and its last run.
 */
export function SettingsPanel({
  step,
  type,
  catalogue,
  check,
  problems,
  sources,
  readOnly,
  run,
  onSetting,
  onRename,
  onDisconnect,
  onConnectMenu,
  onDelete,
  onDuplicate,
}: {
  step: GraphStep;
  type: StepType | undefined;
  catalogue: Catalogue;
  check: PipelineValidation | null;
  problems: PipelineProblem[];
  sources: Source[] | null;
  readOnly: boolean;
  run: StepRunInfo | null;
  onSetting: (key: string, value: Value | undefined, kind: string) => void;
  onRename: (to: string) => string | null;
  onDisconnect: (edge: GraphEdge) => void;
  onConnectMenu: () => void;
  onDelete: () => void;
  onDuplicate: () => void;
}) {
  const [idDraft, setIdDraft] = useState(step.id);
  const [idProblem, setIdProblem] = useState<string | null>(null);
  useEffect(() => {
    setIdDraft(step.id);
    setIdProblem(null);
  }, [step.id]);

  const sourceName = typeof step.settings.source === "string" ? step.settings.source : null;
  const sourceKind = sources?.find((s) => s.name === sourceName)?.kind ?? null;
  const { specs, writerHidden } = shownSettings(type, step, sourceKind);
  const known = new Set(type?.settings.map((s) => s.key) ?? []);
  const extra = Object.keys(step.settings).filter((k) => !known.has(k));
  const assets = useAssets(sourceName);
  const ports = portsOf(step, catalogue);
  const columns = {
    main: arrivingColumns(check, step.id, step.type === "lookup" ? "main" : undefined),
    lookup: step.type === "lookup" ? arrivingColumns(check, step.id, "lookup") : null,
  };
  const bySetting = new Map<string, string[]>();
  const general: PipelineProblem[] = [];
  for (const p of problems) {
    const k = problemSetting(p, step);
    if (k && (known.has(k) || k in step.settings)) bySetting.set(k, [...(bySetting.get(k) ?? []), problemWords(p)]);
    else general.push(p);
  }
  const outline = check?.steps.find((s) => s.id === step.id);
  const base = `cv-set-${step.id}`;

  const rename = () => {
    const to = idDraft.trim();
    if (to === step.id) return;
    const problem = onRename(to);
    setIdProblem(problem);
  };

  return (
    <div className="cv-panel-body">
      <div className="cv-panel-head">
        <span className={`cv-kind-chip cv-kind-${type?.kind ?? "unknown"}`}>
          <StepIcon type={step.type} kind={type?.kind} size={16} />
          {kindWord(type?.kind)} · {type?.label ?? step.type ?? "no type"}
        </span>
        <h3 className="cv-panel-title mono">{step.id}</h3>
        {type?.help && <p className="small muted cv-type-help">{type.help}</p>}
        {!type && step.type && <p className="small pl-bad">This server has no step type called {step.type}.</p>}
      </div>

      <div className="cv-field">
        <label className="cv-label" htmlFor={`${base}-id`}>
          Step id
        </label>
        <p className="hint" id={`${base}-id-help`}>
          Small letters, digits, - and _. Changing it updates the steps reading from it.
        </p>
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
      </div>

      {ports.inputs.length > 0 && (
        <section className="cv-section" aria-labelledby={`${base}-reads`}>
          <h4 id={`${base}-reads`}>Reads from</h4>
          <ul className="cv-links">
            {ports.inputs.map((port) => {
              const links = step.inputs.filter((l) => portOfLink(l, ports) === port);
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
                              aria-label={`Disconnect ${refText(l.from, l.fromPort)} from ${step.id}.${port}`}
                              onClick={() => onDisconnect({ from: l.from, fromPort: l.fromPort, to: step.id, toPort: port })}
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
            Delete step
          </button>
        </div>
      )}

      {general.length > 0 && (
        <ul className="cv-step-problems" aria-label={`Problems with ${step.id}`}>
          {general.map((p, i) => (
            <li key={i}>{problemWords(p)}</li>
          ))}
        </ul>
      )}

      {type && (
        <section className="cv-section" aria-labelledby={`${base}-settings`}>
          <h4 id={`${base}-settings`}>Settings</h4>
          {specs.length === 0 && extra.length === 0 && <p className="small muted">This step has no settings.</p>}
          {specs.map((spec) => (
            <SettingControl
              key={`${step.id}:${spec.key}`}
              spec={spec}
              id={`${base}-${spec.key}`}
              value={step.settings[spec.key]}
              text={step.texts[spec.key]}
              readOnly={readOnly}
              problems={bySetting.get(spec.key) ?? []}
              onChange={(v) => onSetting(spec.key, v, spec.kind)}
              context={{
                columns,
                side: columnSide(step.type, spec.key),
                sources,
                writing: type.kind === "sink",
                assets: spec.kind === "asset" ? assets : null,
                stepType: step.type,
              }}
            />
          ))}
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
              key={`${step.id}:${k}`}
              spec={{ key: k, label: k, kind: "text", required: false, defaultValue: null, help: "A setting this step type does not list.", choices: [] }}
              id={`${base}-${k}`}
              value={step.settings[k]}
              text={step.texts[k]}
              readOnly={readOnly || typeof step.settings[k] === "object"}
              problems={bySetting.get(k) ?? []}
              onChange={(v) => onSetting(k, v, "text")}
              context={{ columns, side: null, sources, writing: false, assets: null, stepType: step.type }}
            />
          ))}
        </section>
      )}

      <section className="cv-section" aria-labelledby={`${base}-cols`}>
        <h4 id={`${base}-cols`}>Columns leaving this step</h4>
        <p className="small">{outline ? columnsText(outline) : "Known once the pipeline has been checked."}</p>
      </section>

      {run && (
        <section className="cv-section" aria-labelledby={`${base}-run`}>
          <h4 id={`${base}-run`}>Last run</h4>
          <p className="small">{runWords(run)}</p>
          {run.error && <p className="small pl-bad">{run.error}</p>}
        </section>
      )}
    </div>
  );
}

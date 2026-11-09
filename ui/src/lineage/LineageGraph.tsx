import { useId, useMemo, useState, type ReactNode } from "react";
import { plural } from "../api";
import { formatIcon, Icon } from "../components/icons";
import { href } from "../router";
import {
  BOX_HEIGHT,
  BOX_WIDTH,
  edgeLabel,
  layoutLineage,
  lineageModel,
  type AssetName,
  type LineageLink,
  type LineageModel,
} from "./lineage";
import "./lineage.css";

/** The icon for an asset's box: a table, or the file's format. */
const iconOf = (a: AssetName) => (a.kind === "table" ? "table" : a.kind === "file" ? formatIcon(a.format ?? "file") : "file");

/** The kind and folder line under a box's name. */
function kindWords(a: AssetName): ReactNode {
  if (a.kind === "table") return "Table";
  if (a.kind === "asset") return "Asset";
  return a.folder ? (
    <>
      File in <span className="lg-folder">{a.folder}/</span>
    </>
  ) : (
    "File at the top level"
  );
}

/** The names of assets, as a sentence's list: "a", "a and b", "a, b and c". */
function names(list: string[]): string {
  if (list.length <= 1) return list.join("");
  return `${list.slice(0, -1).join(", ")} and ${list[list.length - 1]}`;
}

/**
 * What a box's link says beyond what it shows: what the asset came from and what came from it, so
 * moving from box to box reads the lineage without the drawing.
 */
function relations(model: LineageModel, urn: string, byName: Map<string, AssetName>, labels: boolean): string {
  const via = (pipelines: string[]) => (labels && pipelines.length > 0 ? ` (pipeline ${names(pipelines)})` : "");
  // Two assets of one name (orders.csv in two sources) are told apart by their source.
  const nameOf = (u: string) => {
    const a = byName.get(u)!;
    const shared = model.assets.some((b) => b.urn !== u && b.name === a.name);
    return shared && a.source ? `${a.name} in ${a.source}` : a.name;
  };
  const from = model.edges.filter((e) => e.to === urn).map((e) => `${nameOf(e.from)}${via(e.pipelines)}`);
  const into = model.edges.filter((e) => e.from === urn).map((e) => `${nameOf(e.to)}${via(e.pipelines)}`);
  const parts = [
    from.length > 0 ? `came from ${names(from)}` : null,
    model.fromItself.includes(urn) ? "came from itself" : null,
    into.length > 0 ? `went into ${names(into)}` : null,
  ].filter(Boolean);
  return parts.length > 0 ? `: ${parts.join("; ")}.` : ".";
}

/** One sentence saying what the drawing holds, for its caption. */
export function lineageSummary(model: LineageModel, pipeline?: string, centre?: string): string {
  const fed = new Set(model.edges.map((e) => e.to));
  const feeding = new Set(model.edges.map((e) => e.from));
  if (centre) {
    const name = model.assets.find((a) => a.urn === centre)?.name ?? centre;
    const up = model.edges.filter((e) => e.to === centre).length;
    const down = model.edges.filter((e) => e.from === centre).length;
    return `${name} came from ${plural(up, "asset")}, and ${plural(down, "asset")} ${down === 1 ? "was" : "were"} made from it.`;
  }
  const inputs = model.assets.filter((a) => feeding.has(a.urn) && !fed.has(a.urn)).length;
  const outputs = model.assets.filter((a) => fed.has(a.urn)).length;
  return `${plural(inputs, "input")} went through ${pipeline ? `the pipeline ${pipeline}` : "pipelines"} into ${plural(outputs, "output")}.`;
}

/**
 * Lineage drawn left to right: each asset a box (its readable name, kind, folder and source, with
 * its full URN on hover) linking to its catalogue page, and a line for each recorded "came from"
 * pair only, so an output that came from some of the inputs shows exactly those. On a run's page
 * the gap between inputs and outputs is a band naming the run's pipeline (`pipeline`); on an
 * asset's page (`centre`) each line names the pipelines that recorded it. The drawing scrolls
 * inside its frame; the boxes are links whose names say the lineage in words, and the same pairs
 * are listed under "Show the lineage as a list".
 */
export function LineageGraph({
  links,
  pipeline,
  centre,
  label,
}: {
  links: LineageLink[];
  /** The run's pipeline, drawn as the band between inputs and outputs. */
  pipeline?: string;
  /** The asset the graph is about, stressed and marked as this page. */
  centre?: string;
  /** What the figure is, for assistive technology: "Lineage of run 42". */
  label: string;
}) {
  // A marker's id goes in url(#...): keep to letters, digits and dashes.
  const id = `lg${useId().replace(/[^a-zA-Z0-9-]/g, "")}`;
  const labels = !pipeline && links.some((l) => l.pipeline);
  const model = useMemo(() => lineageModel(links), [links]);
  const placed = useMemo(() => layoutLineage(model, { labels }), [model, labels]);
  const byName = useMemo(() => new Map(model.assets.map((a) => [a.urn, a])), [model]);
  const fed = useMemo(() => new Set(model.edges.map((e) => e.to)), [model]);
  // The box pointed at or focused: its lines stand out and the others fade.
  const [focus, setFocus] = useState<string | null>(null);
  const captionId = `${id}-caption`;

  return (
    <figure className="lg" aria-labelledby={`${id}-label`} aria-describedby={captionId}>
      <span id={`${id}-label`} className="visually-hidden">
        {label}
      </span>
      <div className="lg-frame">
        <div className="lg-canvas" style={{ width: placed.width, height: placed.height }}>
          {pipeline && placed.band && (
            <div className="lg-band" style={{ left: placed.band.x, width: placed.band.width }}>
              <span className="lg-band-label" title={`Pipeline ${pipeline}`}>
                Pipeline <span className="mono lg-band-name">{pipeline}</span>
              </span>
            </div>
          )}
          {pipeline && placed.columns && (
            <>
              <span className="lg-heading" aria-hidden="true" style={{ left: placed.columns.first, width: BOX_WIDTH }}>
                Inputs
              </span>
              <span className="lg-heading" aria-hidden="true" style={{ left: placed.columns.last, width: BOX_WIDTH }}>
                Outputs
              </span>
            </>
          )}
          <svg className="lg-lines" width={placed.width} height={placed.height} aria-hidden="true" focusable="false">
            <defs>
              <marker
                id={`${id}-arrow`}
                viewBox="0 0 10 10"
                refX="9"
                refY="5"
                markerWidth="7"
                markerHeight="7"
                orient="auto-start-reverse"
              >
                <path d="M 0 0 L 10 5 L 0 10 z" className="lg-arrow" />
              </marker>
            </defs>
            {placed.edges.map((e) => {
              const near = focus != null && (e.from === focus || e.to === focus);
              return (
                <path
                  key={`${e.from}\n${e.to}`}
                  d={e.path}
                  className={`lg-line${focus == null ? "" : near ? " is-near" : " is-faded"}`}
                  markerEnd={`url(#${id}-arrow)`}
                  data-from={e.from}
                  data-to={e.to}
                />
              );
            })}
          </svg>
          {placed.edges.map(
            (e) =>
              e.label && (
                <span
                  key={`label\n${e.from}\n${e.to}`}
                  className={`lg-edge-label${focus != null && e.from !== focus && e.to !== focus ? " is-faded" : ""}`}
                  aria-hidden="true"
                  title={`Pipeline ${e.pipelines.join(", ")}`}
                  style={{ left: e.label.x, top: e.label.y }}
                >
                  {edgeLabel(e)}
                </span>
              ),
          )}
          {model.assets.map((a) => {
            const p = placed.at.get(a.urn)!;
            const role = a.urn === centre ? "is-centre" : fed.has(a.urn) ? "is-output" : "is-input";
            return (
              <a
                key={a.urn}
                href={href.asset(a.urn)}
                className={`lg-box ${role}`}
                style={{ left: p.x, top: p.y, width: BOX_WIDTH, height: BOX_HEIGHT }}
                title={a.urn}
                aria-current={a.urn === centre ? "page" : undefined}
                onMouseEnter={() => setFocus(a.urn)}
                onMouseLeave={() => setFocus(null)}
                onFocus={() => setFocus(a.urn)}
                onBlur={() => setFocus(null)}
              >
                <span className="lg-name">
                  <Icon name={iconOf(a)} size={16} className="lg-icon" />
                  <span className="mono lg-name-text">{a.name}</span>
                </span>
                <span className="lg-meta">{kindWords(a)}</span>
                <span className="lg-meta lg-source">{a.source ? `in ${a.source}` : "source not known"}</span>
                {model.fromItself.includes(a.urn) && (
                  <span className="lg-self" aria-hidden="true" title="Came from itself">
                    ↻
                  </span>
                )}
                <span className="visually-hidden">{relations(model, a.urn, byName, labels)}</span>
              </a>
            );
          })}
        </div>
      </div>
      <figcaption id={captionId} className="small muted lg-caption">
        {lineageSummary(model, pipeline, centre)} Point at or focus a box to pick out its lines; each opens in the
        catalogue.
      </figcaption>
      <details className="lg-list">
        <summary>Show the lineage as a list</summary>
        <ul className="small">
          {links.map((l, i) => (
            <li key={i}>
              <a href={href.asset(l.output)} className="mono">
                {l.output}
              </a>{" "}
              came from{" "}
              <a href={href.asset(l.input)} className="mono">
                {l.input}
              </a>
              {labels && l.pipeline && (
                <>
                  {" "}
                  through the pipeline <a href={href.pipeline(l.pipeline)}>{l.pipeline}</a>
                </>
              )}
            </li>
          ))}
        </ul>
      </details>
    </figure>
  );
}

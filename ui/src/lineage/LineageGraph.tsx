import { Fragment, useId, useMemo, useState, type ReactNode } from "react";
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

/** How the words name an asset: its name, and its folder and source when another has that name. */
function wordsFor(model: LineageModel, a: AssetName): string {
  const shared = model.assets.some((b) => b.urn !== a.urn && b.name === a.name);
  if (!shared) return a.name;
  const path = a.folder ? `${a.folder}/${a.name}` : a.name;
  return a.source ? `${path} in ${a.source}` : path;
}

/**
 * What a box says beyond what it shows, as its description: what the asset came from and went
 * into, so moving from box to box reads the lineage without the drawing.
 */
export function relations(model: LineageModel, urn: string, labels: boolean): string {
  const byUrn = new Map(model.assets.map((a) => [a.urn, a]));
  const via = (pipelines: string[]) => (labels && pipelines.length > 0 ? ` (pipeline ${names(pipelines)})` : "");
  const from = model.edges.filter((e) => e.to === urn).map((e) => `${wordsFor(model, byUrn.get(e.from)!)}${via(e.pipelines)}`);
  const into = model.edges.filter((e) => e.from === urn).map((e) => `${wordsFor(model, byUrn.get(e.to)!)}${via(e.pipelines)}`);
  if (model.fromItself.includes(urn)) from.push("itself");
  const parts = [from.length > 0 ? `Came from ${names(from)}.` : null, into.length > 0 ? `Went into ${names(into)}.` : null];
  return parts.filter(Boolean).join(" ");
}

/** The assets a run wrote: those that came from something, themselves included. */
const writtenOf = (model: LineageModel) => new Set([...model.edges.map((e) => e.to), ...model.fromItself]);

/** One sentence saying what the drawing holds, for its caption. */
export function lineageSummary(model: LineageModel, pipeline?: string, centre?: string): string {
  if (centre) {
    const name = model.assets.find((a) => a.urn === centre)?.name ?? centre;
    const up = model.edges.filter((e) => e.to === centre).length;
    const down = model.edges.filter((e) => e.from === centre).length;
    const self = model.fromItself.includes(centre);
    const from = up === 0 && self ? "itself" : `${plural(up, "asset")}${self ? " and itself" : ""}`;
    return `${name} came from ${from}, and ${plural(down, "asset")} ${down === 1 ? "was" : "were"} made from it.`;
  }
  const written = writtenOf(model);
  const feeding = new Set(model.edges.map((e) => e.from));
  const inputs = model.assets.filter((a) => feeding.has(a.urn) && !written.has(a.urn)).length;
  const by = pipeline ? `the pipeline ${pipeline}` : "pipelines";
  const base =
    inputs > 0
      ? `${plural(inputs, "input")} went through ${by} into ${plural(written.size, "output")}.`
      : `${by.charAt(0).toUpperCase()}${by.slice(1)} wrote ${plural(written.size, "output")}.`;
  const selves = model.fromItself.length;
  if (selves === 0) return base;
  return `${base} ${selves === 1 ? "One" : selves} also came from ${selves === 1 ? "itself" : "themselves"}.`;
}

/**
 * Lineage drawn left to right: each asset a box (its readable name, kind, folder and source, with
 * its full URN on hover) linking to its catalogue page, and a line for each recorded "came from"
 * pair only, so an output that came from some of the inputs shows exactly those. On a run's page
 * the gap between inputs and outputs is a band naming the run's pipeline (`pipeline`); on an
 * asset's page (`centre`) each line names the pipelines that recorded it. The drawing scrolls
 * inside its frame; the boxes are links, in the drawing's order, described by what they came from
 * and went into, and the same pairs are listed under "Show the lineage as a list".
 */
export function LineageGraph({
  links,
  pipeline,
  centre,
  centreKind,
  label,
}: {
  links: LineageLink[];
  /** The run's pipeline, drawn as the band between inputs and outputs. */
  pipeline?: string;
  /** The asset the graph is about, stressed and marked as this page. */
  centre?: string;
  /** The centre's kind as the catalogue knows it, rather than guessed from its URN. */
  centreKind?: "table" | "file";
  /** What the figure is, for assistive technology: "Lineage of run 42". */
  label: string;
}) {
  // An id that goes in url(#...): keep to letters, digits and dashes.
  const id = `lg${useId().replace(/[^a-zA-Z0-9-]/g, "")}`;
  const labels = !pipeline && links.some((l) => l.pipeline);
  const model = useMemo(
    () => lineageModel(links, centre && centreKind ? { [centre]: centreKind } : {}),
    [links, centre, centreKind],
  );
  const placed = useMemo(() => layoutLineage(model, { labels }), [model, labels]);
  const written = useMemo(() => writtenOf(model), [model]);
  // Boxes in the drawing's order, column by column, top to bottom: the order Tab takes.
  const ordered = useMemo(
    () =>
      [...model.assets].sort((a, b) => {
        const p = placed.at.get(a.urn)!;
        const q = placed.at.get(b.urn)!;
        return p.x - q.x || p.y - q.y;
      }),
    [model, placed],
  );
  // The box pointed at, else the one focused: its lines stand out and the others fade.
  const [hovered, setHovered] = useState<string | null>(null);
  const [focused, setFocused] = useState<string | null>(null);
  const focus = hovered ?? focused;
  const captionId = `${id}-caption`;

  return (
    <div className="lg">
      <figure className="lg-figure" aria-labelledby={`${id}-label`} aria-describedby={captionId}>
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
                {["", "-near"].map((kind) => (
                  <marker
                    key={kind}
                    id={`${id}-arrow${kind}`}
                    viewBox="0 0 10 10"
                    refX="9"
                    refY="5"
                    markerUnits="userSpaceOnUse"
                    markerWidth="10"
                    markerHeight="10"
                    orient="auto-start-reverse"
                  >
                    <path d="M 0 0 L 10 5 L 0 10 z" className={`lg-arrow${kind ? " is-near" : ""}`} />
                  </marker>
                ))}
              </defs>
              {placed.edges.map((e) => {
                const near = focus != null && (e.from === focus || e.to === focus);
                return (
                  <path
                    key={`${e.from}\n${e.to}`}
                    d={e.path}
                    className={`lg-line${e.back ? " is-back" : ""}${focus == null ? "" : near ? " is-near" : " is-faded"}`}
                    markerEnd={`url(#${id}-arrow${near ? "-near" : ""})`}
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
            {ordered.map((a, i) => {
              const p = placed.at.get(a.urn)!;
              const role = a.urn === centre ? "is-centre" : written.has(a.urn) ? "is-output" : "is-input";
              const said = relations(model, a.urn, labels);
              return (
                <Fragment key={a.urn}>
                <a
                  href={href.asset(a.urn)}
                  className={`lg-box ${role}`}
                  style={{ left: p.x, top: p.y, width: BOX_WIDTH, height: BOX_HEIGHT }}
                  title={a.urn}
                  aria-current={a.urn === centre ? "page" : undefined}
                  aria-describedby={said ? `${id}-said-${i}` : undefined}
                  onMouseEnter={() => setHovered(a.urn)}
                  onMouseLeave={() => setHovered(null)}
                  onFocus={() => setFocused(a.urn)}
                  onBlur={() => setFocused(null)}
                >
                  <span className="lg-name">
                    <Icon name={iconOf(a)} size={16} className="lg-icon" />
                    <span className="mono lg-name-text">{a.name}</span>
                  </span>
                  <span className="lg-meta">{kindWords(a)}</span>
                  <span className="lg-meta lg-source">
                    {a.source ? `in ${a.source}` : "source not known"}
                    {model.fromItself.includes(a.urn) && (
                      <span className="lg-self" aria-hidden="true" title="Came from itself">
                        {" "}
                        · ↻ came from itself
                      </span>
                    )}
                  </span>
                </a>
                {/* Outside the link and hidden: it describes the link without being its name or read twice. */}
                {said && (
                  <span id={`${id}-said-${i}`} hidden>
                    {said}
                  </span>
                )}
                </Fragment>
              );
            })}
          </div>
        </div>
        <figcaption id={captionId} className="small muted lg-caption">
          {lineageSummary(model, pipeline, centre)} Point at or focus a box to pick out its lines; each opens in the
          catalogue.
        </figcaption>
      </figure>
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
    </div>
  );
}
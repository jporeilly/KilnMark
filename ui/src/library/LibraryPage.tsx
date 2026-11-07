import { useCallback, useEffect, useState } from "react";
import {
  allows,
  ApiError,
  flowsApi,
  pipelinesApi,
  samplesApi,
  type AccessRole,
  type FlowSummary,
  type PipelineSummary,
  type SampleCatalogue,
  type SampleExplanation,
  type SampleInfo,
  type Template,
} from "../api";
import { Tabs, TabPanel, useTabsId } from "../components/ui";
import { useTitle } from "../components/useTitle";
import { docHref } from "../docs/links";
import { href } from "../router";
import { AddSamples } from "../pipelines/AddSamples";
import { RunStatus } from "../pipelines/RunStatus";
import { FlowRunStatus } from "../flows/RunFlow";
import "../pipelines/pipelines.css";
import "./library.css";

type Tab = "samples" | "templates";

/** "01-read-and-write/filter.yaml" → "Read and write": the heading of a sample's folder. */
export function groupOf(file: string): string {
  const folder = file.includes("/") ? file.slice(0, file.indexOf("/")) : "";
  const words = folder.replace(/^\d+-/, "").replace(/-/g, " ").trim();
  return words ? words[0]!.toUpperCase() + words.slice(1) : "Other";
}

/** The samples in their folders, in the order the server lists them. */
export function groupSamples(samples: SampleInfo[]): [string, SampleInfo[]][] {
  const groups = new Map<string, SampleInfo[]>();
  for (const s of samples) {
    const g = groupOf(s.file);
    groups.set(g, [...(groups.get(g) ?? []), s]);
  }
  return [...groups.entries()];
}

/** Whether an explanation has anything to show beyond its title. */
export function explains(e: SampleExplanation | null): e is SampleExplanation {
  return !!e && !!(e.shows || e.why || e.lookFor || e.needs);
}

/**
 * A sample's or template's explanation, from the comment its file starts with: what it shows (for a
 * template, what it is for), why it matters, what to look for, and what it needs. Parts left out are
 * not shown.
 */
export function Explanation({ explanation: e, template = false }: { explanation: SampleExplanation | null; template?: boolean }) {
  if (!explains(e)) return null;
  const parts: [string, string | null][] = [
    [template ? "What it is for" : "What it shows", e.shows],
    ["Why it matters", e.why],
    [template ? "Look for" : "Look for when it runs", e.lookFor],
    ["Needs", e.needs],
  ];
  return (
    <dl className="lib-explain">
      {parts
        .filter((p): p is [string, string] => !!p[1])
        .map(([label, text]) => (
          <div key={label}>
            <dt>{label}</dt>
            <dd>{text}</dd>
          </div>
        ))}
    </dl>
  );
}

/**
 * The Library (ADR 0043): Kilnmark's samples and templates, kept apart from a team's own pipelines
 * and flows. Samples show one idea each and run on Kilnmark's sample data once added; templates are
 * starting points, opened in the editor under a name of one's own.
 */
export function LibraryPage({ role, tab }: { role: AccessRole; tab: Tab }) {
  useTitle(tab === "templates" ? "Library: templates" : "Library: samples");
  const designer = allows(role, "designer");
  const tabsId = useTabsId();
  return (
    <>
      <div className="pl-head">
        <h1 tabIndex={-1}>Library</h1>
      </div>
      <p className="lead">
        Kilnmark&apos;s samples and templates, kept apart from your own pipelines and flows.{" "}
        <strong>Samples</strong> show one idea each and run on Kilnmark&apos;s sample data.{" "}
        <strong>Templates</strong> are starting points for your own work. <a href={docHref("library")}>About the Library</a>.
      </p>
      <Tabs
        label="What the Library shows"
        idBase={tabsId}
        selected={tab}
        onSelect={(t) => {
          window.location.hash = href.library(t as Tab);
        }}
        tabs={[
          { id: "samples", label: "Samples" },
          { id: "templates", label: "Templates" },
        ]}
      />
      <TabPanel idBase={tabsId} selected={tab}>
        {tab === "samples" ? <SamplesTab designer={designer} /> : <TemplatesTab designer={designer} />}
      </TabPanel>
    </>
  );
}

function SamplesTab({ designer }: { designer: boolean }) {
  const [catalogue, setCatalogue] = useState<SampleCatalogue | null>(null);
  const [off, setOff] = useState(false);
  const [pipelines, setPipelines] = useState<PipelineSummary[]>([]);
  const [flows, setFlows] = useState<FlowSummary[]>([]);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    samplesApi
      .list()
      .then((c) => {
        setCatalogue(c);
        setOff(false);
        setError(null);
      })
      .catch((e: Error) => {
        if (e instanceof ApiError && e.status === 403) setOff(true);
        else setError(e.message);
      });
    pipelinesApi.list().then(setPipelines, () => {});
    flowsApi.list().then(setFlows, () => {});
  }, []);
  useEffect(load, [load]);

  if (off) {
    return (
      <p className="panel">
        This server does not offer the samples: an admin turns them on under <a href={href.settings()}>Settings</a>.
        The templates are still here, on the other tab.
      </p>
    );
  }
  const added = catalogue?.samples.filter((s) => s.exists).length ?? 0;
  return (
    <>
      <div className="lib-bar">
        <p>
          {catalogue
            ? `${catalogue.samples.length} samples; ${added} added here. Added samples run like any pipeline or flow, and stay listed here, never on the Pipelines or Flows page.`
            : ""}
        </p>
        {designer && <AddSamples onAdded={load} />}
      </div>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {catalogue === null && !error && <p role="status">Loading…</p>}
      {catalogue?.tables && (
        <p className="hint">
          The sample tables are in {catalogue.tables.source}, schema {catalogue.tables.schema}
          {catalogue.tables.lastRun && (
            <>
              : <RunStatus run={catalogue.tables.lastRun} />
            </>
          )}
        </p>
      )}
      {catalogue &&
        groupSamples(catalogue.samples).map(([group, samples]) => (
          <section key={group} className="panel lib-group" aria-label={group}>
            <h2>{group}</h2>
            <ul className="lib-list">
              {samples.map((s) => (
                <SampleRow
                  key={s.file}
                  sample={s}
                  pipeline={pipelines.find((p) => p.name === s.name)}
                  flow={flows.find((f) => f.name === s.name)}
                />
              ))}
            </ul>
          </section>
        ))}
    </>
  );
}

function SampleRow({ sample: s, pipeline, flow }: { sample: SampleInfo; pipeline?: PipelineSummary; flow?: FlowSummary }) {
  const link = s.exists ? (s.type === "flow" ? href.flow(s.name) : href.pipeline(s.name)) : null;
  return (
    <li className="lib-item">
      <div className="lib-item-main">
        <span className={`lib-kind lib-kind--${s.type}`}>{s.type === "flow" ? "Flow" : "Pipeline"}</span>
        {link ? <a href={link}>{s.name}</a> : <span className="mono">{s.name}</span>}
        {s.description && <div className="small muted">{s.description}</div>}
        {explains(s.explanation) && (
          <details className="lib-about">
            <summary>
              About this sample<span className="visually-hidden">: {s.name}</span>
            </summary>
            <Explanation explanation={s.explanation} />
          </details>
        )}
      </div>
      <div className="lib-item-state small">
        {!s.exists && <span className="muted">Not added</span>}
        {s.exists && s.type === "pipeline" && <RunStatus run={pipeline?.lastRun ?? null} />}
        {s.exists && s.type === "flow" && <FlowRunStatus run={flow?.lastRun ?? null} />}
      </div>
    </li>
  );
}

function TemplatesTab({ designer }: { designer: boolean }) {
  const [templates, setTemplates] = useState<Template[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    samplesApi.templates().then(setTemplates, (e: Error) => setError(e.message));
  }, []);
  return (
    <>
      <p className="lib-bar-text">
        Each template opens in the editor as a new pipeline or flow. Give it a name, replace each{" "}
        <span className="mono">&lt;…&gt;</span> with your own source, table or pipeline, and save it: it is then yours,
        on the Pipelines or Flows page.
      </p>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {templates === null && !error && <p role="status">Loading…</p>}
      {templates && (
        <ul className="lib-cards">
          {templates.map((t) => (
            <li key={t.file} className="panel lib-card">
              <span className={`lib-kind lib-kind--${t.type}`}>{t.type === "flow" ? "Flow" : "Pipeline"}</span>
              <h2 id={`tpl-${t.name}`}>{t.description ?? t.name}</h2>
              <p className="small muted mono">{t.name}</p>
              <Explanation explanation={t.explanation} template />
              <details>
                <summary>Show the YAML</summary>
                <pre className="lib-yaml">{t.yaml}</pre>
              </details>
              {designer ? (
                <a
                  className="pl-link-button"
                  href={t.type === "flow" ? href.newFlow(t.file) : href.newPipeline(t.file)}
                  aria-describedby={`tpl-${t.name}`}
                >
                  Use this template
                </a>
              ) : (
                <p className="small muted">Designers start pipelines and flows from templates.</p>
              )}
            </li>
          ))}
        </ul>
      )}
    </>
  );
}

import { useCallback, useEffect, useMemo, useState } from "react";
import {
  aiApi,
  allows,
  api,
  ApiError,
  call,
  formatNumber,
  formatScore,
  pipelinesApi,
  qualityApi,
  type Issue,
  type Me,
  type PipelineRun,
  type PipelineSummary,
  type Source,
  plural,
} from "../api";
import { href } from "../router";
import { formatAgo, greeting } from "../format";
import { useTitle } from "../components/useTitle";
import { Icon } from "../components/icons";
import { SourceTile } from "../components/ConnectorTile";
import { useConnectorInfo } from "../theme/looks";
import {
  Button,
  ButtonLink,
  Card,
  DataTable,
  EmptyState,
  Meter,
  SeverityChip,
  StatTile,
  StatusChip,
  type Tone,
} from "../components/ui";
import { NotificationList } from "../notifications/NotificationList";
import { markNotificationsRead, useNotifications } from "../notifications/store";
import { NOTIFICATION_SETTINGS, OPEN_NOTIFICATIONS, SENT_EMAILS } from "../notifications/NotificationBell";
import { Who } from "../components/Who";

type SourceScore = { source: string; scores: Record<string, number>; assets: { urn: string; score: number }[] };

/** What a card is showing: still loading, the data, or why it could not be loaded. */
type Load<T> = { state: "loading" } | { state: "ok"; data: T } | { state: "error"; message: string };

/** Loads once (and again on retry), keeping loading, data and error apart so no card says "all good" on a failure. */
function useLoad<T>(load: () => Promise<T>): [Load<T>, () => void] {
  const [value, setValue] = useState<Load<T>>({ state: "loading" });
  const [round, setRound] = useState(0);
  useEffect(() => {
    let live = true;
    setValue({ state: "loading" });
    load()
      .then((data) => live && setValue({ state: "ok", data }))
      .catch((e: Error) => live && setValue({ state: "error", message: e.message }));
    return () => {
      live = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [round]);
  return [value, useCallback(() => setRound((n) => n + 1), [])];
}

const ok = <T,>(l: Load<T>): T | undefined => (l.state === "ok" ? l.data : undefined);

/** null when the server does not have the endpoint (404), so its tile stays hidden. */
async function optional<T>(load: () => Promise<T>): Promise<T | null> {
  try {
    return await load();
  } catch (e) {
    if (e instanceof ApiError && (e.status === 404 || e.status === 405)) return null;
    throw e;
  }
}

/** The most the issues list returns; a full list is shown as "500+". */
const ISSUE_LIMIT = 500;
/** Source cards on the home page; only these have their scores fetched. */
const SOURCE_CARDS = 6;

const busy = (s: Source) => s.lastRun?.status === "queued" || s.lastRun?.status === "running";
const pct = formatScore;
const runTime = (r: PipelineRun) => Date.parse(r.finishedAt ?? r.startedAt ?? r.requestedAt);

/** A card's load failure: what went wrong and Try again. */
function LoadError({ what, message, onRetry }: { what: string; message: string; onRetry: () => void }) {
  return (
    <div className="km-load-error" role="alert">
      <p>
        <strong>Could not load {what}.</strong> <span className="muted">{message}</span>
      </p>
      <Button size="sm" onClick={onRetry}>
        Try again
      </Button>
    </div>
  );
}

/**
 * Home: a greeting with what needs attention, four KPI tiles, the sources with their health and
 * score, what needs a look (open issues, including drift), notifications (when the server has
 * them), the latest pipeline runs (when it has pipelines), and a setup checklist driven by what has
 * actually been done. Every number comes from the API; each card says when it is loading or could
 * not be loaded, and a tile whose data the server does not have is left out.
 */
export function HomePage({ me }: { me: Me }) {
  useTitle("");
  const steward = allows(me.role, "steward");
  const designer = allows(me.role, "designer");
  const admin = allows(me.role, "admin");
  const notesOn = useNotifications().available === true;

  const [sources, reloadSources] = useLoad(() => api.sources());
  const [issues, reloadIssues] = useLoad(() => qualityApi.issues("open", ISSUE_LIMIT));
  const [rules] = useLoad(() => qualityApi.rules().then((r) => r.length));
  // Only an admin can see model profiles, and only a local one ticks "Connect a local model".
  const [localModel] = useLoad(() =>
    admin ? aiApi.profiles().then((p) => p.some((x) => x.local)) : Promise.resolve<boolean | null>(null),
  );
  const [pipelines, reloadPipelines] = useLoad(() => optional(() => pipelinesApi.list()));

  // Scores for the source cards shown only: one request per card, never one per source.
  const shown = useMemo(() => ok(sources)?.slice(0, SOURCE_CARDS) ?? [], [sources]);
  // A source's scores, or null when they could not be loaded (so the tile stops saying Loading).
  const [scores, setScores] = useState<Record<string, SourceScore | null>>({});
  useEffect(() => {
    let live = true;
    shown.forEach((s) =>
      call<SourceScore>(`/api/v1/quality/sources/${encodeURIComponent(s.name)}`)
        .then((q) => live && setScores((m) => ({ ...m, [s.name]: q })))
        .catch(() => live && setScores((m) => ({ ...m, [s.name]: null }))),
    );
    return () => {
      live = false;
    };
  }, [shown]);

  const sourceList = ok(sources);
  const shownScores = shown.map((s) => scores[s.name]?.scores?.overall).filter((x): x is number => x != null);
  const scoresPending = shown.some((s) => !(s.name in scores));
  const meanScore = shownScores.length ? shownScores.reduce((a, b) => a + b, 0) / shownScores.length : null;
  const failing = (sourceList ?? []).filter((s) => s.lastRun?.status === "failed" || s.lastRun?.status === "partial");
  const assets = (sourceList ?? []).reduce((n, s) => n + s.assets, 0);
  const openList = ok(issues);
  const full = (openList?.length ?? 0) >= ISSUE_LIMIT;
  const openCount = openList ? `${formatNumber(openList.length)}${full ? "+" : ""}` : "–";
  const high = (openList ?? []).filter((i) => i.severity === "high").length;
  const medium = (openList ?? []).filter((i) => i.severity === "medium").length;
  const pipelineList = ok(pipelines);
  const runs =
    pipelineList == null
      ? null
      : pipelineList
          .map((p) => p.lastRun)
          .filter((r): r is PipelineRun => r != null)
          .sort((a, b) => runTime(b) - runTime(a))
          .slice(0, 5);
  const lastRun = runs?.find((r) => r.status !== "queued" && r.status !== "running");

  const summary = [
    sourceList == null
      ? null
      : sourceList.length === 0
        ? "No sources yet"
        : failing.length
          ? `${failing.length} of ${sourceList.length} ${sourceList.length === 1 ? "source needs" : "sources need"} attention`
          : `${plural(sourceList.length, "source")} healthy`,
    lastRun ? `last pipeline run ${RUN_WORD[lastRun.status]} ${formatAgo(lastRun.finishedAt ?? lastRun.requestedAt)}` : null,
    openList == null
      ? null
      : openList.length === 0
        ? "nothing needs a look"
        : `${openCount} ${openList.length === 1 ? "issue needs" : "issues need"} a look`,
  ].filter(Boolean);
  const canPipeline = pipelineList != null && designer;

  return (
    <div className="km-stack">
      <section className="km-hero" aria-labelledby="hello-h">
        <div className="km-hero-text">
          <h1 id="hello-h" tabIndex={-1}>
            {greeting()}, {me.displayName || me.name}
          </h1>
          <p>{summary.length ? summary.join(" · ") : "Loading what changed…"}</p>
        </div>
        <div className="km-hero-actions">
          {canPipeline && (
            <ButtonLink variant="primary" href={href.newPipeline()} icon={<Icon name="plus" size={16} />}>
              New pipeline
            </ButtonLink>
          )}
          {designer ? (
            <ButtonLink
              variant={canPipeline ? "secondary" : "primary"}
              href={href.addConnection()}
              icon={canPipeline ? undefined : <Icon name="plus" size={16} />}
            >
              Add a connection
            </ButtonLink>
          ) : (
            <ButtonLink href={href.browse()}>Browse the catalogue</ButtonLink>
          )}
        </div>
      </section>

      <section aria-label="At a glance" className="km-grid-4">
        <StatTile
          label="Sources"
          icon={<Icon name="sources" size={18} />}
          tint="sky"
          value={sourceList ? formatNumber(sourceList.length) : "–"}
          note={
            sources.state === "loading"
              ? "Loading…"
              : sources.state === "error"
                ? "Could not load"
                : sourceList!.length === 0
                  ? "None connected yet"
                  : failing.length
                    ? `${failing.length} with a failed scan`
                    : sourceList!.some(busy)
                      ? "All healthy, one scanning"
                      : "All healthy"
          }
          noteTone={sources.state !== "ok" ? "neutral" : failing.length ? "bad" : sourceList!.length ? "good" : "neutral"}
          href={href.sources()}
        />
        <StatTile
          label="Assets catalogued"
          icon={<Icon name="table" size={18} />}
          tint="violet"
          value={sourceList ? formatNumber(assets) : "–"}
          note={
            sources.state === "loading"
              ? "Loading…"
              : sources.state === "error"
                ? "Could not load"
                : `Across ${plural(sourceList!.length, "source")}`
          }
          href={href.browse()}
        />
        <StatTile
          label="Quality score"
          icon={<Icon name="rules" size={18} />}
          tint="green"
          value={meanScore == null ? "–" : pct(meanScore)}
          note={
            sources.state === "loading" || scoresPending
              ? "Loading…"
              : sources.state === "error"
                ? "Could not load"
                : meanScore == null
                  ? "Not scored yet: scan a source"
                  : `Average of ${shownScores.length} source ${shownScores.length === 1 ? "score" : "scores"}${
                      (sourceList?.length ?? 0) > shown.length ? ", the first shown" : ""
                    }`
          }
          noteTone="neutral"
          href={href.rules()}
        />
        <StatTile
          label="Open issues"
          icon={<Icon name="issues" size={18} />}
          tint="orange"
          value={openCount}
          note={
            issues.state === "loading"
              ? "Loading…"
              : issues.state === "error"
                ? "Could not load"
                : openList!.length === 0
                  ? "Nothing open"
                  : full
                    ? `At least ${formatNumber(ISSUE_LIMIT)}`
                    : [high && `${high} high`, medium && `${medium} medium`, openList!.length - high - medium && `${openList!.length - high - medium} low`]
                        .filter(Boolean)
                        .join(", ")
          }
          noteTone={issues.state !== "ok" ? "neutral" : high ? "bad" : openList!.length ? "warn" : "good"}
          href={href.issues()}
        />
      </section>

      <SetupCard
        sources={sourceList ?? null}
        rules={ok(rules) ?? null}
        localModel={ok(localModel) ?? null}
        pipelines={pipelines.state === "ok" ? pipelines.data : undefined}
        admin={admin}
        steward={steward}
        designer={designer}
      />

      {runs ? (
        // Two columns that end level: runs and sources on the left; what needs a look and what
        // happened on the right.
        <div className="km-columns km-columns--level">
          <div className="km-column">
            <RecentRuns runs={runs} />
            <SourcesCard sources={sources} shown={shown} scores={scores} designer={designer} onRetry={reloadSources} />
          </div>
          <div className="km-column">
            <NeedsALook issues={issues} full={full} onRetry={reloadIssues} />
            {notesOn && <NotificationsCard admin={admin} />}
          </div>
        </div>
      ) : (
        // Without pipelines: sources across the page, then the two lists side by side.
        <>
          {pipelines.state === "error" && (
            <LoadError what="pipelines" message={pipelines.message} onRetry={reloadPipelines} />
          )}
          <SourcesCard sources={sources} shown={shown} scores={scores} designer={designer} onRetry={reloadSources} />
          <div className={notesOn ? "km-halves" : undefined}>
            <NeedsALook issues={issues} full={full} onRetry={reloadIssues} />
            {notesOn && <NotificationsCard admin={admin} />}
          </div>
        </>
      )}

      <TrendStrip />
    </div>
  );
}

const RUN_WORD: Record<PipelineRun["status"], string> = {
  queued: "queued",
  running: "running",
  succeeded: "succeeded",
  failed: "failed",
  cancelled: "was cancelled",
};
const RUN_CHIP: Record<PipelineRun["status"], { tone: Tone; label: string }> = {
  queued: { tone: "neutral", label: "Queued" },
  running: { tone: "info", label: "Running" },
  succeeded: { tone: "good", label: "Succeeded" },
  failed: { tone: "bad", label: "Failed" },
  cancelled: { tone: "neutral", label: "Cancelled" },
};

/** The latest run of each pipeline, newest first, at most five (from GET /api/v1/pipelines). */
function RecentRuns({ runs }: { runs: PipelineRun[] }) {
  return (
    <Card
      title="Recent pipeline runs"
      titleId="runs-h"
      subtitle="The latest run of each pipeline"
      className="km-card--fill"
      actions={<a href={href.pipelines()}>All pipelines</a>}
    >
      {runs.length === 0 ? (
        <EmptyState compact icon={<Icon name="pipelines" size={22} />} title="No runs yet">
          Runs appear here once a pipeline has run.
        </EmptyState>
      ) : (
        <DataTable label="Recent pipeline runs (scrolls sideways on small screens)" caption="The latest run of each pipeline">
          <thead>
            <tr>
              <th scope="col">Pipeline</th>
              <th scope="col">Status</th>
              <th scope="col">Rows written</th>
              <th scope="col">When</th>
            </tr>
          </thead>
          <tbody>
            {runs.map((r) => (
              <tr key={r.id}>
                <th scope="row">
                  <a href={href.pipelineRun(r.id)} className="km-strong-link">
                    {r.pipeline}
                  </a>
                  <div className="small muted">
                    version {r.version} · by <Who name={r.requestedBy} />
                  </div>
                </th>
                <td>
                  <StatusChip tone={RUN_CHIP[r.status]?.tone ?? "neutral"} dot>
                    {RUN_CHIP[r.status]?.label ?? r.status}
                  </StatusChip>
                  {r.error && (
                    <div className="small muted km-clip" title={r.error}>
                      {r.error}
                    </div>
                  )}
                </td>
                <td className="num">{formatNumber(r.rowsWritten)}</td>
                <td className="small muted">
                  {r.status === "running" ? `started ${formatAgo(r.startedAt ?? r.requestedAt)}` : formatAgo(r.finishedAt ?? r.requestedAt)}
                </td>
              </tr>
            ))}
          </tbody>
        </DataTable>
      )}
    </Card>
  );
}

const STATE: Record<string, { tone: Tone; word: string }> = {
  queued: { tone: "info", word: "Scan queued" },
  running: { tone: "info", word: "Scanning…" },
  succeeded: { tone: "good", word: "Scanned" },
  partial: { tone: "warn", word: "Partly scanned" },
  failed: { tone: "bad", word: "Scan failed" },
};

const short = new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" });

/** The first six sources as cards: tile, name, kind and what it scans, assets, score and health. */
function SourcesCard({
  sources,
  shown,
  scores,
  designer,
  onRetry,
}: {
  sources: Load<Source[]>;
  shown: Source[];
  scores: Record<string, SourceScore | null>;
  /** Whether the viewer may add sources (designers and admins). */
  designer: boolean;
  onRetry: () => void;
}) {
  const connectors = useConnectorInfo();
  const total = ok(sources)?.length ?? 0;
  return (
    <Card
      title="Sources"
      titleId="src-h"
      className="km-card--fill"
      actions={<a href={href.sources()}>{total > shown.length ? `All ${plural(total, "source")}` : "Manage sources"}</a>}
    >
      {sources.state === "loading" && <p className="muted">Loading sources…</p>}
      {sources.state === "error" && <LoadError what="sources" message={sources.message} onRetry={onRetry} />}
      {sources.state === "ok" && total === 0 && (
        <EmptyState
          icon={<Icon name="sources" size={22} />}
          title="No sources yet"
          action={designer ? <ButtonLink variant="primary" href={href.addConnection()}>Add a connection</ButtonLink> : undefined}
        >
          {designer ? "Add a database, folder or bucket and scan it to fill the catalogue." : "Ask a designer to add a source."}
        </EmptyState>
      )}
      {shown.length > 0 && (
        <ul className="km-source-cards">
          {shown.map((s) => {
            const state = s.lastRun ? STATE[s.lastRun.status] : { tone: "neutral" as Tone, word: "Never scanned" };
            const when = s.lastRun?.finishedAt ?? null;
            const score = scores[s.name]?.scores?.overall ?? null;
            const where = s.settings.schemas || s.settings.containers || s.settings.bucket || s.settings.path || "";
            const kind = `${connectors[s.kind]?.displayName ?? s.kind}${where ? ` · ${where.split(",").join(", ")}` : ""}`;
            return (
              <li key={s.name}>
                <a className="km-inner" href={href.source(s.name)}>
                  <span className="with-tile">
                    <SourceTile kind={s.kind} size="md" />
                    <span className="km-row-body">
                      <span className="km-row-title km-one-line" title={s.displayName}>
                        {s.displayName}
                      </span>
                      <span className="km-row-detail km-one-line" title={kind}>
                        {kind}
                      </span>
                    </span>
                  </span>
                  <span className="km-source-facts">
                    <span className="num">
                      <strong>{formatNumber(s.assets)}</strong> {s.assets === 1 ? "asset" : "assets"}
                    </span>
                    <span className="num">
                      <strong>{score == null ? "–" : pct(score)}</strong> quality
                    </span>
                  </span>
                  <span className={`km-state km-text-${state.tone}`}>
                    <span className="km-chip-dot" aria-hidden="true" />
                    {state.word}
                    {when && s.lastRun?.status !== "running" && ` ${short.format(new Date(when))}`}
                  </span>
                </a>
              </li>
            );
          })}
        </ul>
      )}
    </Card>
  );
}

/** Open issues, most severe and newest first: failing rules and drift between scans. */
function NeedsALook({ issues, full, onRetry }: { issues: Load<Issue[]>; full: boolean; onRetry: () => void }) {
  const rank = { high: 0, medium: 1, low: 2 } as const;
  const list = ok(issues) ?? [];
  const top = [...list]
    .sort((a, b) => rank[a.severity] - rank[b.severity] || Date.parse(b.createdAt) - Date.parse(a.createdAt))
    .slice(0, 4);
  return (
    <Card title="Needs a look" titleId="iss-h" subtitle="Things to fix" className="km-card--fill">
      {issues.state === "loading" && <p className="muted">Loading issues…</p>}
      {issues.state === "error" && <LoadError what="issues" message={issues.message} onRetry={onRetry} />}
      {issues.state === "ok" && list.length === 0 && (
        <EmptyState compact icon={<Icon name="circle-check" size={22} />} title="Nothing needs a look">
          Failing rules and drift between scans show here.
        </EmptyState>
      )}
      {top.length > 0 && (
        <ul className="km-rows">
          {top.map((i) => {
            const asset = i.assetUrn.replace(/^urn:kilnmark:[^:]+:/, "");
            const detail = `${i.driftEventId != null ? "Drift · " : ""}${asset}${i.detail ? ` · ${i.detail}` : ""}`;
            return (
              <li key={i.id} className="km-row">
                <SeverityChip severity={i.severity} />
                <div className="km-row-body">
                  <h3 className="km-row-title">
                    <a href={href.asset(i.assetUrn, "quality")}>{i.title}</a>
                  </h3>
                  <p className="km-row-detail km-one-line" title={detail}>
                    {detail}
                  </p>
                </div>
              </li>
            );
          })}
        </ul>
      )}
      <div className="km-card-foot">
        <a href={href.issues()}>
          {list.length > top.length ? `View all ${formatNumber(list.length)}${full ? "+" : ""} open issues` : "View issues"}
        </a>
      </div>
    </Card>
  );
}

/** What happened: the latest three notifications, whole. Hidden when the server has no notifications. */
function NotificationsCard({ admin }: { admin: boolean }) {
  const n = useNotifications();
  const [message, setMessage] = useState("");
  if (n.available !== true) return null;
  const items = n.items ?? [];
  return (
    <Card
      title="Notifications"
      titleId="notes-h"
      subtitle="What happened"
      className="km-card--fill"
      actions={
        n.unread > 0 ? (
          <button
            type="button"
            className="link-button"
            onClick={() =>
              markNotificationsRead("all")
                .then(() => setMessage("All notifications marked read."))
                .catch((e: Error) => setMessage(e.message))
            }
          >
            Mark all read
          </button>
        ) : undefined
      }
    >
      <p role="status" className="visually-hidden">
        {message}
      </p>
      {n.error && <p className="error">{n.error}</p>}
      {n.items === null && <p className="muted">Loading notifications…</p>}
      {n.items?.length === 0 && (
        <EmptyState compact icon={<Icon name="circle-check" size={22} />} title="You're all caught up">
          New notifications will show here.
        </EmptyState>
      )}
      {items.length > 0 && (
        <NotificationList
          items={items.slice(0, 3)}
          onOpen={(x) => {
            if (!x.read) void markNotificationsRead([x.id]).catch(() => undefined);
          }}
        />
      )}
      <div className="km-card-foot">
        {items.length > 3 && (
          <button
            type="button"
            className="link-button"
            onClick={() => window.dispatchEvent(new CustomEvent(OPEN_NOTIFICATIONS))}
          >
            View all
          </button>
        )}
        <a href={NOTIFICATION_SETTINGS}>Notification settings</a>
        {admin && <a href={SENT_EMAILS}>Sent emails</a>}
      </div>
    </Card>
  );
}

/**
 * Quality over the last 14 days. The server keeps each asset's latest scores only, with no daily
 * history, so there is nothing to draw: a slim line says so, rather than a full card or an
 * invented chart. Replace it with the chart once score history exists.
 */
function TrendStrip() {
  return (
    <section className="km-strip" aria-labelledby="trend-h">
      <Icon name="chart-line" size={18} />
      <h2 id="trend-h" className="km-strip-title">
        Quality over the last 14 days
      </h2>
      <p className="km-strip-text">
        No trend yet: Kilnmark keeps each asset's latest scores, not a daily history. Rule results over time are on an
        asset's Quality tab.
      </p>
    </section>
  );
}

type Step = { id: string; label: string; done: boolean; link: string | null; cta: string };

const SETUP_DISMISSED = "kilnmark.setupDismissed";

/** The steps a dismissal was for: a new step (pipelines arriving, say) brings the checklist back. */
const readDismissed = () => {
  try {
    return window.localStorage.getItem(SETUP_DISMISSED);
  } catch {
    return null;
  }
};

/**
 * Getting set up: each step is ticked from what the server says has been done, across the page
 * in one row. Once every step is done it shrinks to one line, which can be dismissed (remembered
 * in this browser for this set of steps).
 */
function SetupCard({
  sources,
  rules,
  localModel,
  pipelines,
  admin,
  steward,
  designer,
}: {
  sources: Source[] | null;
  rules: number | null;
  /** Whether a local model is set up; null when this role cannot tell (only admins see models). */
  localModel: boolean | null;
  /** The pipelines, null when the server has none (404), undefined while loading. */
  pipelines: PipelineSummary[] | null | undefined;
  admin: boolean;
  steward: boolean;
  designer: boolean;
}) {
  const [dismissed, setDismissed] = useState(readDismissed);
  if (sources === null || rules === null || pipelines === undefined) return null;
  const scanned = sources.some((s) => s.lastRun?.status === "succeeded" || s.lastRun?.status === "partial" || s.assets > 0);
  const steps: Step[] = [
    { id: "source", label: "Connect a source", done: sources.length > 0, link: designer ? href.addConnection() : null, cta: "Add a connection" },
    { id: "scan", label: "Scan and profile it", done: scanned, link: steward ? href.sources() : null, cta: "Scan a source" },
    { id: "rule", label: "Add a quality rule", done: rules > 0, link: steward ? href.rules() : null, cta: "Add a rule" },
  ];
  if (localModel !== null)
    steps.push({ id: "model", label: "Connect a local model", done: localModel, link: admin ? href.ai() : null, cta: "Connect a local model" });
  if (pipelines !== null)
    steps.push({
      id: "pipeline",
      label: "Build your first pipeline",
      done: pipelines.length > 0,
      link: designer ? href.newPipeline() : null,
      cta: "Build a pipeline",
    });
  const key = steps.map((s) => s.id).join(",");
  const done = steps.filter((s) => s.done).length;
  const next = steps.find((s) => !s.done);

  if (!next) {
    if (dismissed === key) return null;
    return (
      <section className="km-strip km-strip--good" aria-labelledby="setup-h">
        <Icon name="circle-check" size={18} />
        <h2 id="setup-h" className="km-strip-title">
          All set up
        </h2>
        <p className="km-strip-text">
          Sources connected and scanned, rules added{localModel ? ", a local model connected" : ""}
          {pipelines?.length ? " and a pipeline built" : ""}.
        </p>
        <button
          type="button"
          className="km-icon-button km-strip-close"
          aria-label="Dismiss All set up"
          onClick={() => {
            try {
              window.localStorage.setItem(SETUP_DISMISSED, key);
            } catch {
              // Storage blocked: dismissed until the page is reloaded.
            }
            setDismissed(key);
          }}
        >
          <Icon name="close" size={18} />
        </button>
      </section>
    );
  }

  return (
    <Card
      title="Getting set up"
      titleId="setup-h"
      className="km-setup-card"
      actions={
        next.link ? (
          <a href={next.link}>
            {next.cta} <Icon name="arrow-right" size={16} />
          </a>
        ) : (
          <span className="hint">Next: {next.label.toLowerCase()}. Ask an admin or steward.</span>
        )
      }
    >
      <div className="km-setup-progress">
        <span id="setup-progress" className="km-card-sub">
          {done} of {steps.length} done
        </span>
        <Meter value={done / steps.length} size="lg" gradient />
      </div>
      <ol className="km-setup" aria-describedby="setup-progress">
        {steps.map((s, i) => (
          <li key={s.id} className={s.done ? "is-done" : s === next ? "is-next" : undefined}>
            <span className="km-setup-mark" aria-hidden="true">
              {s.done ? <Icon name="check" size={14} /> : i + 1}
            </span>
            <span className="km-setup-label">
              {s.label}
              <span className="visually-hidden">{s.done ? ": done" : ": not done yet"}</span>
            </span>
          </li>
        ))}
      </ol>
    </Card>
  );
}

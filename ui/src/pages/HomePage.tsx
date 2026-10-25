import { useEffect, useMemo, useState } from "react";
import {
  aiApi,
  allows,
  api,
  ApiError,
  call,
  formatNumber,
  qualityApi,
  type Issue,
  type Me,
  type Source,
} from "../api";
import { href } from "../router";
import { formatAgo, greeting } from "../format";
import { useTitle } from "../components/useTitle";
import { Icon } from "../components/icons";
import { SourceTile } from "../components/ConnectorTile";
import { useConnectorInfo } from "../theme/looks";
import {
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
import { NOTIFICATION_SETTINGS, SENT_EMAILS } from "../notifications/NotificationBell";

/** A pipeline run as the pipelines branch reports it (GET /api/v1/pipeline-runs). */
type PipelineRun = {
  id: number;
  pipeline: string;
  version: number;
  status: "queued" | "running" | "succeeded" | "failed" | "cancelled";
  requestedBy: string;
  requestedAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  error: string | null;
  rowsWritten: number | null;
};

type SourceScore = { source: string; scores: Record<string, number>; assets: { urn: string; score: number }[] };

/** null: not on this server (404); undefined: still loading. */
async function optional<T>(path: string): Promise<T | null> {
  try {
    return await call<T>(path);
  } catch (e) {
    if (e instanceof ApiError && (e.status === 404 || e.status === 405)) return null;
    throw e;
  }
}

const busy = (s: Source) => s.lastRun?.status === "queued" || s.lastRun?.status === "running";
const pct = (score: number) => `${Math.round(score * 100)}%`;

/**
 * Home: a greeting with what needs attention, four KPI tiles, the sources with their health and
 * score, what needs a look (open issues, including drift), notifications (when the server has
 * them), recent pipeline runs (when it has pipelines), the quality trend, and a setup checklist
 * driven by what has actually been done. Every number comes from the API; a tile whose data the
 * server does not have is left out or says so.
 */
export function HomePage({ me }: { me: Me }) {
  useTitle("");
  const steward = allows(me.role, "steward");
  const admin = allows(me.role, "admin");
  const [sources, setSources] = useState<Source[] | null>(null);
  const [scores, setScores] = useState<Record<string, SourceScore>>({});
  const [issues, setIssues] = useState<Issue[] | null>(null);
  const [rules, setRules] = useState<number | null>(null);
  const [model, setModel] = useState<boolean | null>(null);
  const [pipelines, setPipelines] = useState<number | null | undefined>(undefined);
  const [runs, setRuns] = useState<PipelineRun[] | null | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    api
      .sources()
      .then((list) => {
        if (!live) return;
        setSources(list);
        list.forEach((s) =>
          call<SourceScore>(`/api/v1/quality/sources/${encodeURIComponent(s.name)}`)
            .then((q) => live && setScores((m) => ({ ...m, [s.name]: q })))
            .catch(() => undefined),
        );
      })
      .catch((e: Error) => live && setError(e.message));
    qualityApi
      .issues("open")
      .then((i) => live && setIssues(i))
      .catch(() => live && setIssues([]));
    qualityApi
      .rules()
      .then((r) => live && setRules(r.length))
      .catch(() => live && setRules(null));
    aiApi
      .status()
      .then((s) => live && setModel(s.searchByMeaning || s.ask))
      .catch(() => live && setModel(null));
    optional<unknown[]>("/api/v1/pipelines")
      .then((p) => live && setPipelines(p ? p.length : null))
      .catch(() => live && setPipelines(null));
    optional<PipelineRun[] | { items: PipelineRun[] }>("/api/v1/pipeline-runs?limit=5")
      .then((r) => live && setRuns(r == null ? null : Array.isArray(r) ? r : r.items))
      .catch(() => live && setRuns(null));
    return () => {
      live = false;
    };
  }, []);

  const assetScores = useMemo(() => Object.values(scores).flatMap((s) => s.assets.map((a) => a.score)), [scores]);
  const overall = assetScores.length ? assetScores.reduce((a, b) => a + b, 0) / assetScores.length : null;
  const failing = (sources ?? []).filter((s) => s.lastRun?.status === "failed" || s.lastRun?.status === "partial");
  const assets = (sources ?? []).reduce((n, s) => n + s.assets, 0);
  const open = issues ?? [];
  const high = open.filter((i) => i.severity === "high").length;
  const medium = open.filter((i) => i.severity === "medium").length;
  const lastRun = runs?.find((r) => r.status !== "queued" && r.status !== "running");

  const summary = [
    sources === null
      ? null
      : sources.length === 0
        ? "No sources yet"
        : failing.length
          ? `${failing.length} of ${sources.length} ${sources.length === 1 ? "source needs" : "sources need"} attention`
          : `${sources.length} ${sources.length === 1 ? "source" : "sources"} healthy`,
    lastRun ? `last pipeline run ${RUN_WORD[lastRun.status]} ${formatAgo(lastRun.finishedAt ?? lastRun.requestedAt)}` : null,
    issues === null ? null : open.length === 0 ? "nothing needs a look" : `${open.length} ${open.length === 1 ? "issue needs" : "issues need"} a look`,
  ].filter(Boolean);

  return (
    <div className="km-stack">
      <section className="km-hero" aria-labelledby="hello-h">
        <div className="km-hero-text">
          <h1 id="hello-h" tabIndex={-1}>
            {greeting()}, {me.name}
          </h1>
          <p>{summary.length ? summary.join(" · ") : "Loading what changed…"}</p>
        </div>
        <div className="km-hero-actions">
          {pipelines != null && steward && (
            <ButtonLink variant="primary" href="#/pipelines/new" icon={<Icon name="plus" size={16} />}>
              New pipeline
            </ButtonLink>
          )}
          {admin ? (
            <ButtonLink
              variant={pipelines != null && steward ? "secondary" : "primary"}
              href={href.addConnection()}
              icon={pipelines != null && steward ? undefined : <Icon name="plus" size={16} />}
            >
              Add a connection
            </ButtonLink>
          ) : (
            <ButtonLink href={href.browse()}>Browse the catalogue</ButtonLink>
          )}
        </div>
      </section>

      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}

      <section aria-label="At a glance" className="km-grid-4">
        <StatTile
          label="Sources"
          icon={<Icon name="sources" size={18} />}
          tint="sky"
          value={sources ? formatNumber(sources.length) : "–"}
          note={
            sources === null
              ? "Loading…"
              : sources.length === 0
                ? "None connected yet"
                : failing.length
                  ? `${failing.length} with a failed scan`
                  : sources.some(busy)
                    ? "All healthy, one scanning"
                    : "All healthy"
          }
          noteTone={failing.length ? "bad" : sources?.length ? "good" : "neutral"}
          href={href.sources()}
        />
        <StatTile
          label="Assets catalogued"
          icon={<Icon name="table" size={18} />}
          tint="violet"
          value={sources ? formatNumber(assets) : "–"}
          note={sources ? `Across ${sources.length} ${sources.length === 1 ? "source" : "sources"}` : "Loading…"}
          href={href.browse()}
        />
        <StatTile
          label="Quality score"
          icon={<Icon name="rules" size={18} />}
          tint="green"
          value={overall == null ? "–" : pct(overall)}
          note={
            overall == null
              ? "Not scored yet: scan a source"
              : `Mean of ${formatNumber(assetScores.length)} scored ${assetScores.length === 1 ? "asset" : "assets"}`
          }
          noteTone={overall == null ? "neutral" : overall >= 0.95 ? "good" : overall >= 0.8 ? "neutral" : "warn"}
          href={href.rules()}
        />
        <StatTile
          label="Open issues"
          icon={<Icon name="issues" size={18} />}
          tint="orange"
          value={issues ? formatNumber(open.length) : "–"}
          note={
            issues === null
              ? "Loading…"
              : open.length === 0
                ? "Nothing open"
                : [high && `${high} high`, medium && `${medium} medium`, open.length - high - medium && `${open.length - high - medium} low`]
                    .filter(Boolean)
                    .join(", ")
          }
          noteTone={high ? "bad" : open.length ? "warn" : "good"}
          href={href.issues()}
        />
      </section>

      <div className="km-columns">
        <div className="km-column">
          {runs && <RecentRuns runs={runs} />}
          <SourcesCard sources={sources} scores={scores} admin={admin} />
          <TrendCard />
        </div>
        <div className="km-column">
          <SetupCard
            sources={sources}
            rules={rules}
            model={model}
            pipelines={pipelines}
            admin={admin}
            steward={steward}
          />
          <NeedsALook issues={issues} />
          <NotificationsCard admin={admin} />
        </div>
      </div>
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

/** The last five pipeline runs. Shown only when the server has pipelines. */
function RecentRuns({ runs }: { runs: PipelineRun[] }) {
  return (
    <Card title="Recent pipeline runs" titleId="runs-h" actions={<a href="#/pipelines">All pipelines</a>}>
      {runs.length === 0 ? (
        <EmptyState compact icon={<Icon name="pipelines" size={22} />} title="No runs yet">
          Runs appear here once a pipeline has run.
        </EmptyState>
      ) : (
        <DataTable label="Recent pipeline runs (scrolls sideways on small screens)" caption="The last five pipeline runs">
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
                  <a href={`#/pipeline-runs/${r.id}`} className="km-strong-link">
                    {r.pipeline}
                  </a>
                  <div className="small muted">
                    version {r.version} · by {r.requestedBy}
                  </div>
                </th>
                <td>
                  <StatusChip tone={RUN_CHIP[r.status]?.tone ?? "neutral"} dot>
                    {RUN_CHIP[r.status]?.label ?? r.status}
                  </StatusChip>
                  {r.error && <div className="small muted km-clip">{r.error}</div>}
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

/** Up to six sources as cards: tile, name, kind and what it scans, assets, score and health. */
function SourcesCard({ sources, scores, admin }: { sources: Source[] | null; scores: Record<string, SourceScore>; admin: boolean }) {
  const connectors = useConnectorInfo();
  return (
    <Card title="Sources" titleId="src-h" actions={<a href={href.sources()}>Manage sources</a>}>
      {sources === null && <p role="status" className="muted">Loading sources…</p>}
      {sources?.length === 0 && (
        <EmptyState
          icon={<Icon name="sources" size={22} />}
          title="No sources yet"
          action={admin ? <ButtonLink variant="primary" href={href.addConnection()}>Add a connection</ButtonLink> : undefined}
        >
          {admin ? "Add a database, folder or bucket and scan it to fill the catalogue." : "Ask an admin to add a source."}
        </EmptyState>
      )}
      {sources && sources.length > 0 && (
        <ul className="km-source-cards">
          {sources.slice(0, 6).map((s) => {
            const state = s.lastRun ? STATE[s.lastRun.status] : { tone: "neutral" as Tone, word: "Never scanned" };
            const when = s.lastRun?.finishedAt ?? null;
            const score = scores[s.name]?.scores?.overall ?? null;
            const where = s.settings.schemas || s.settings.containers || s.settings.bucket || s.settings.path || "";
            return (
              <li key={s.name}>
                <a className="km-inner" href={href.source(s.name)}>
                  <span className="with-tile">
                    <SourceTile kind={s.kind} size="md" />
                    <span className="km-row-body">
                      <span className="km-row-title">{s.displayName}</span>
                      <span className="km-row-detail">
                        {connectors[s.kind]?.displayName ?? s.kind}
                        {where && ` · ${where.split(",").join(", ")}`}
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
function NeedsALook({ issues }: { issues: Issue[] | null }) {
  const rank = { high: 0, medium: 1, low: 2 } as const;
  const top = [...(issues ?? [])]
    .sort((a, b) => rank[a.severity] - rank[b.severity] || Date.parse(b.createdAt) - Date.parse(a.createdAt))
    .slice(0, 4);
  return (
    <Card title="Needs a look" titleId="iss-h" actions={<a href={href.issues()}>Issues</a>} subtitle="Things to fix">
      {issues === null && <p role="status" className="muted">Loading issues…</p>}
      {issues?.length === 0 && (
        <EmptyState compact icon={<Icon name="circle-check" size={22} />} title="Nothing needs a look">
          Failing rules and drift between scans show here.
        </EmptyState>
      )}
      {top.length > 0 && (
        <ul className="km-rows">
          {top.map((i) => {
            const asset = i.assetUrn.replace(/^urn:kilnmark:[^:]+:/, "");
            return (
              <li key={i.id} className="km-row">
                <SeverityChip severity={i.severity} />
                <div className="km-row-body">
                  <h3 className="km-row-title">
                    <a href={href.asset(i.assetUrn, "quality")}>{i.title}</a>
                  </h3>
                  <p className="km-row-detail">
                    {i.driftEventId != null ? "Drift · " : ""}
                    {asset}
                    {i.detail ? ` · ${i.detail}` : ""}
                  </p>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </Card>
  );
}

/** What happened: the latest six notifications. Hidden when the server has no notifications. */
function NotificationsCard({ admin }: { admin: boolean }) {
  const n = useNotifications();
  const [message, setMessage] = useState("");
  if (n.available !== true) return null;
  return (
    <Card
      title="Notifications"
      titleId="notes-h"
      subtitle="What happened"
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
      {n.items && n.items.length > 0 && (
        <NotificationList
          items={n.items}
          onOpen={(x) => {
            if (!x.read) void markNotificationsRead([x.id]).catch(() => undefined);
          }}
        />
      )}
      <div className="km-card-foot">
        <a href={NOTIFICATION_SETTINGS}>Notification settings</a>
        {admin && <a href={SENT_EMAILS}>Sent emails</a>}
      </div>
    </Card>
  );
}

/**
 * Quality over the last 14 days. The server keeps each asset's latest scores only, with no daily
 * history, so there is nothing to draw yet: say so plainly rather than draw an invented line.
 */
function TrendCard() {
  return (
    <Card title="Quality over the last 14 days" titleId="trend-h">
      <EmptyState icon={<Icon name="chart-line" size={22} />} title="No trend to show yet">
        <p>
          Kilnmark keeps each asset's latest quality scores, not a daily history, so there is no trend to draw. Today's
          scores are on each source and asset; rule results over time are on an asset's Quality tab.
        </p>
      </EmptyState>
    </Card>
  );
}

type Step = { id: string; label: string; done: boolean; link: string | null; cta: string };

/** Getting set up: each step is ticked from what the server says has been done. */
function SetupCard({
  sources,
  rules,
  model,
  pipelines,
  admin,
  steward,
}: {
  sources: Source[] | null;
  rules: number | null;
  model: boolean | null;
  pipelines: number | null | undefined;
  admin: boolean;
  steward: boolean;
}) {
  if (sources === null) return null;
  const scanned = sources.some((s) => s.lastRun?.status === "succeeded" || s.lastRun?.status === "partial" || s.assets > 0);
  const steps: Step[] = [
    { id: "source", label: "Connect a source", done: sources.length > 0, link: admin ? href.addConnection() : null, cta: "Add a connection" },
    { id: "scan", label: "Scan and profile it", done: scanned, link: steward ? href.sources() : null, cta: "Scan a source" },
    { id: "rule", label: "Add a quality rule", done: (rules ?? 0) > 0, link: steward ? href.rules() : null, cta: "Add a rule" },
    { id: "model", label: "Connect a local model", done: model === true, link: admin ? href.ai() : null, cta: "Connect a local model" },
  ];
  // Only on servers with pipelines (the pipelines endpoint answers).
  if (pipelines != null)
    steps.push({ id: "pipeline", label: "Build your first pipeline", done: pipelines > 0, link: steward ? "#/pipelines/new" : null, cta: "Build a pipeline" });
  const done = steps.filter((s) => s.done).length;
  const next = steps.find((s) => !s.done);
  return (
    <Card title="Getting set up" titleId="setup-h">
      <p className="km-card-sub" id="setup-progress">
        {done} of {steps.length} done
      </p>
      <span className="km-progress">
        <Meter value={done / steps.length} size="lg" gradient />
      </span>
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
      {next ? (
        next.link ? (
          <p className="km-card-next">
            <a href={next.link}>
              {next.cta} <Icon name="arrow-right" size={16} />
            </a>
          </p>
        ) : (
          <p className="km-card-next hint">Next: {next.label.toLowerCase()}. Ask an admin or steward to do this.</p>
        )
      ) : (
        <p className="km-card-next km-text-good">All set up.</p>
      )}
    </Card>
  );
}

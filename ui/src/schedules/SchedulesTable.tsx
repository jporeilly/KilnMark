import { useCallback, useEffect, useRef, useState } from "react";
import {
  allows,
  formatTime,
  scheduleChange,
  schedulesApi,
  type AccessRole,
  type Schedule,
  type ScheduleTarget,
} from "../api";
import { href } from "../router";
import { DataTable, StatusChip } from "../components/ui";
import { Who } from "../components/Who";
import { cronWords, outcomeOf, valuesText, whenText } from "./cron";

/** One pipeline's or flow's schedules (or every schedule), read again on demand. */
export function useSchedules(target: ScheduleTarget | null, enabled = true) {
  const key = target == null ? "" : "pipeline" in target ? `p:${target.pipeline}` : `f:${target.flow}`;
  const [schedules, setSchedules] = useState<Schedule[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  // Only the latest answer counts, so a slow look never undoes a newer one.
  const sequence = useRef(0);
  const reload = useCallback(() => {
    if (!enabled) return;
    const n = ++sequence.current;
    schedulesApi
      .list(key === "" ? undefined : key.startsWith("p:") ? { pipeline: key.slice(2) } : { flow: key.slice(2) })
      .then((s) => {
        if (sequence.current !== n) return;
        setSchedules(s);
        setError(null);
      })
      .catch((e: Error) => sequence.current === n && setError(e.message));
  }, [key, enabled]);
  useEffect(reload, [reload]);
  return { schedules, error, reload };
}

/** The earliest next run of the schedules that are on, or null. */
export function nextRun(schedules: Schedule[] | null): Schedule | null {
  let first: Schedule | null = null;
  for (const s of schedules ?? []) {
    if (s.enabled && s.nextAt && (!first || Date.parse(s.nextAt) < Date.parse(first.nextAt!))) first = s;
  }
  return first;
}

/**
 * Its next scheduled run, in a few words beside its last run, as a button that shows the
 * Schedules tab; nothing when it has no schedules.
 */
export function ScheduledNext({ schedules, onShow }: { schedules: Schedule[] | null; onShow: () => void }) {
  if (!schedules || schedules.length === 0) return null;
  const next = nextRun(schedules);
  return (
    <>
      {" "}
      <button type="button" className="link-button" onClick={onShow}>
        {next?.nextAt
          ? `Next scheduled run: ${whenText(next.nextAt, next.zone)}.`
          : schedules.every((s) => !s.enabled)
            ? schedules.length === 1
              ? "Its schedule is paused."
              : "Its schedules are paused."
            : "No scheduled run to come."}
      </button>
    </>
  );
}

/** A schedule's times in words where that is easy, else its expression. */
export const scheduleWords = (s: Schedule) => cronWords(s.cron) ?? `At the times ${s.cron} gives`;

/**
 * Pause, resume, delete and opening one to edit, for designers and admins. Pausing and resuming
 * read the schedule again and send it back with only `enabled` changed, so a change someone made
 * since the list was read is kept; the API replaces everything, and makes the person saving its
 * new "set by". Deleting asks first, then moves the focus to the message saying it is done.
 */
export function useScheduleActions(reload: () => void) {
  const [notice, setNotice] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<number | null>(null);
  const noticeRef = useRef<HTMLParagraphElement>(null);
  const toggle = async (s: Schedule) => {
    if (busy != null) return;
    setBusy(s.id);
    setError(null);
    try {
      const now = await schedulesApi.get(s.id);
      await schedulesApi.replace(s.id, { ...scheduleChange(now), enabled: !now.enabled });
      setNotice(
        now.enabled
          ? `Schedule ${s.id} is paused, and is now set by you.`
          : `Schedule ${s.id} is resumed, and is now set by you: its runs carry your name.`,
      );
      reload();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
    }
  };
  const remove = async (s: Schedule) => {
    if (busy != null) return;
    if (!window.confirm(`Delete schedule ${s.id} (${scheduleWords(s)})? It stops running. The runs it started stay.`)) return;
    setBusy(s.id);
    setError(null);
    try {
      await schedulesApi.remove(s.id);
      setNotice(`Schedule ${s.id} is deleted.`);
      // Its row and the button that had the focus are gone: the focus goes to the message.
      window.setTimeout(() => noticeRef.current?.focus(), 0);
      reload();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
    }
  };
  /** Opens a schedule to edit as it is now, not as the list last read it. */
  const open = async (s: Schedule, edit: (fresh: Schedule) => void) => {
    setError(null);
    try {
      edit(await schedulesApi.get(s.id));
    } catch (e) {
      setError((e as Error).message);
    }
  };
  return { notice, setNotice, noticeRef, error, setError, busy, toggle, remove, open };
}

/** What happened the last time a schedule was due: in words, with a tone, the reason and the run. */
export function LastOutcome({ s }: { s: Schedule }) {
  const o = outcomeOf(s.lastOutcome);
  const run = s.lastPipelineRun ?? s.lastFlowRun;
  return (
    <>
      <StatusChip tone={o.tone} dot kind="outcome">
        {o.word}
      </StatusChip>
      {s.lastAt && <div className="small muted">{formatTime(s.lastAt)}</div>}
      {o.reason && <div className="small">{o.reason}</div>}
      {run != null && (
        <div className="small">
          <a href={s.lastPipelineRun != null ? href.pipelineRun(run) : href.flowRun(run)}>
            {s.lastPipelineRun != null ? "Pipeline" : "Flow"} run {run}
          </a>
        </div>
      )}
    </>
  );
}

/** Whether it is on, in words. */
export function ScheduleState({ s }: { s: Schedule }) {
  return s.enabled ? (
    <StatusChip tone="good" kind="state">
      On
    </StatusChip>
  ) : (
    <StatusChip tone="neutral" kind="state">
      Paused
    </StatusChip>
  );
}

/**
 * Schedules in a table: when (in words, with the expression and zone, and the values its runs start
 * with for stewards and above), who set it and the role its runs have, whether it is on and its
 * next run in the viewer's own time, and its last run. Designers and admins have Edit, Pause or
 * Resume, and Delete. With `names`, a column says what each runs, linked.
 */
export function SchedulesTable({
  schedules,
  role,
  label,
  names,
  busy,
  onEdit,
  onToggle,
  onDelete,
}: {
  schedules: Schedule[];
  role: AccessRole;
  label: string;
  /** Show what each runs, by display name when known. */
  names?: Map<string, string>;
  busy?: number | null;
  onEdit?: (s: Schedule) => void;
  onToggle?: (s: Schedule) => void;
  onDelete?: (s: Schedule) => void;
}) {
  const steward = allows(role, "steward");
  const writes = allows(role, "designer") && onEdit != null;
  return (
    <DataTable label={`${label} (scrolls sideways on small screens)`} className="sc-table">
      <thead>
        <tr>
          <th scope="col">Schedule</th>
          {names && <th scope="col">Runs</th>}
          <th scope="col">Set by</th>
          <th scope="col">State and next run</th>
          <th scope="col">Last time it was due</th>
          {writes && (
            <th scope="col">
              <span className="visually-hidden">Actions</span>
            </th>
          )}
        </tr>
      </thead>
      <tbody>
        {schedules.map((s) => {
          const target = s.pipeline != null ? { kind: "Pipeline", name: s.pipeline } : { kind: "Flow", name: s.flow ?? "" };
          return (
            <tr key={s.id}>
              <th scope="row">
                <div className="sc-words">{scheduleWords(s)}</div>
                <div className="small muted">
                  <span className="mono">{s.cron}</span>, {s.zone} · schedule {s.id}
                </div>
                {s.description && <div className="small sc-description">{s.description}</div>}
                {steward && (
                  <div className="small sc-values">
                    <span className="muted">Values:</span> {valuesText(s.parameters)}
                  </div>
                )}
              </th>
              {names && (
                <td>
                  <div className="small muted">{target.kind}</div>
                  <a className="sc-target" href={s.pipeline != null ? href.pipeline(target.name) : href.flow(target.name)}>
                    {names.get(`${target.kind}:${target.name}`) ?? target.name}
                  </a>
                </td>
              )}
              <td>
                <div className="sc-setby">
                  <Who name={s.updatedBy} />
                </div>
                <div className="small muted">Runs as {s.runAs}</div>
                <div className="small muted sc-nowrap">{formatTime(s.updatedAt)}</div>
                {s.createdBy !== s.updatedBy && (
                  <div className="small muted">
                    Added by <Who name={s.createdBy} />
                  </div>
                )}
              </td>
              <td>
                <ScheduleState s={s} />
                <div className="small sc-next">
                  {s.nextAt ? whenText(s.nextAt, s.zone) : s.enabled ? "No next run: it no longer fires" : "No next run while paused"}
                </div>
              </td>
              <td>
                <LastOutcome s={s} />
              </td>
              {writes && (
                <td>
                  <div className="buttons tight">
                    <button type="button" onClick={() => onEdit?.(s)} aria-disabled={busy === s.id ? true : undefined} aria-label={`Edit schedule ${s.id}`}>
                      Edit
                    </button>
                    <button
                      type="button"
                      onClick={() => onToggle?.(s)}
                      aria-disabled={busy === s.id ? true : undefined}
                      aria-label={`${s.enabled ? "Pause" : "Resume"} schedule ${s.id}`}
                    >
                      {s.enabled ? "Pause" : "Resume"}
                    </button>
                    <button type="button" onClick={() => onDelete?.(s)} aria-disabled={busy === s.id ? true : undefined} aria-label={`Delete schedule ${s.id}`}>
                      Delete
                    </button>
                  </div>
                </td>
              )}
            </tr>
          );
        })}
      </tbody>
    </DataTable>
  );
}

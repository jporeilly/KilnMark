import { useEffect, useMemo, useState } from "react";
import { allows, flowsApi, pipelinesApi, type AccessRole, type Schedule } from "../api";
import { go, href, type ScheduleFilter } from "../router";
import { useTitle } from "../components/useTitle";
import { docHref } from "../docs/links";
import { Card, EmptyState, FilterChips, PageHeader } from "../components/ui";
import { lastFailed, pausedBySetter } from "./cron";
import { ScheduleForm, targetOf } from "./ScheduleForm";
import { SchedulesTable, useScheduleActions, useSchedules } from "./SchedulesTable";
import "./schedules.css";

const SHOWS: Record<ScheduleFilter, (s: Schedule) => boolean> = {
  all: () => true,
  on: (s) => s.enabled,
  paused: (s) => !s.enabled,
  setter: pausedBySetter,
  failed: lastFailed,
};

/**
 * Every schedule on the server (ADR 0046): what it runs, linked; when; who set it, whose name its
 * runs carry; whether it is on; its next run and what happened last time. Filters show those on,
 * paused, paused because whoever set them lost the role, or whose last time failed. Admins use it
 * in access reviews: a schedule pauses itself when whoever set it loses the role (a token revoked
 * or expired, a sign-in with less of a role), and an admin resumes it, becoming its setter, or
 * deletes it. Designers and admins change, pause, resume and delete schedules here; they add them
 * on a pipeline or flow.
 */
export function SchedulesPage({ role, show }: { role: AccessRole; show: ScheduleFilter }) {
  useTitle("Schedules");
  const designer = allows(role, "designer");
  const admin = allows(role, "admin");
  const data = useSchedules(null);
  const { schedules, error: loadError, reload } = data;
  const actions = useScheduleActions(reload);
  const [editing, setEditing] = useState<Schedule | null>(null);
  const [names, setNames] = useState<Map<string, string>>(new Map());

  // Display names, for the Runs column; the names themselves serve until they come.
  useEffect(() => {
    let live = true;
    Promise.all([pipelinesApi.list().catch(() => []), flowsApi.list().catch(() => [])]).then(([ps, fs]) => {
      if (!live) return;
      setNames(
        new Map([
          ...ps.map((p) => [`Pipeline:${p.name}`, p.displayName] as [string, string]),
          ...fs.map((f) => [`Flow:${f.name}`, f.displayName] as [string, string]),
        ]),
      );
    });
    return () => {
      live = false;
    };
  }, []);

  useEffect(() => {
    const t = window.setInterval(reload, 30_000);
    return () => window.clearInterval(t);
  }, [reload]);

  const counts = useMemo(() => {
    const all = schedules ?? [];
    return {
      all: all.length,
      on: all.filter(SHOWS.on).length,
      paused: all.filter(SHOWS.paused).length,
      setter: all.filter(SHOWS.setter).length,
      failed: all.filter(SHOWS.failed).length,
    };
  }, [schedules]);
  const shown = schedules?.filter(SHOWS[show]) ?? null;
  const people = useMemo(() => new Set((schedules ?? []).map((s) => s.updatedBy)).size, [schedules]);

  return (
    <>
      <PageHeader
        title="Schedules"
        lead={
          <>
            Pipelines and flows that run at set times. Each run is recorded as asked for by its schedule, in the name of
            whoever set it last, with the role shown. Add a schedule on a pipeline&apos;s or flow&apos;s page, in its
            Schedules tab. <a href={docHref("schedules")}>How schedules work</a>.
          </>
        }
      />
      <p role="status" className="notice" tabIndex={-1} ref={actions.noticeRef}>
        {actions.notice}
      </p>
      {(actions.error || loadError) && (
        <p role="alert" className="error">
          {actions.error ?? loadError}
        </p>
      )}
      <Card title="All schedules" titleId="schedules-h" className="km-section">
        <FilterChips
          label="Show"
          selected={show}
          onSelect={(id) => go(href.schedules(id as ScheduleFilter))}
          options={[
            { id: "all", label: "All", count: counts.all },
            { id: "on", label: "On", count: counts.on, tone: "green" },
            { id: "paused", label: "Paused", count: counts.paused },
            { id: "setter", label: "Paused: setter lost the role", count: counts.setter, tone: "amber" },
            { id: "failed", label: "Failed last time", count: counts.failed, tone: "red" },
          ]}
        />
        {admin && schedules && schedules.length > 0 && (
          <p className="hint sc-review">
            For access reviews: {schedules.length === 1 ? "this schedule runs" : `these ${schedules.length} schedules run`}{" "}
            in the name of {people === 1 ? "one person" : `${people} people`}, under <strong>Set by</strong>. When
            someone&apos;s token is revoked or expires, or they lose their role, Kilnmark pauses the schedules they set:
            resume each one, which makes you its setter, or delete it. Choose{" "}
            <strong>Paused: setter lost the role</strong> to see them.
          </p>
        )}
        {schedules === null && !loadError && (
          <p role="status" className="muted">
            Loading…
          </p>
        )}
        {schedules?.length === 0 && (
          <EmptyState title="No schedules yet" compact>
            {designer
              ? "Open a pipeline or a flow and choose Add a schedule in its Schedules tab."
              : "Designers add schedules on a pipeline's or flow's page."}
          </EmptyState>
        )}
        {shown && schedules && schedules.length > 0 && shown.length === 0 && (
          <p className="muted">
            None here. <a href={href.schedules()}>Show every schedule</a>.
          </p>
        )}
        {shown && shown.length > 0 && (
          <SchedulesTable
            schedules={shown}
            role={role}
            label="Schedules"
            names={names}
            busy={actions.busy}
            onEdit={designer ? (s) => void actions.open(s, setEditing) : undefined}
            onToggle={designer ? actions.toggle : undefined}
            onDelete={designer ? actions.remove : undefined}
          />
        )}
      </Card>
      {!designer && (
        <p className="hint">
          Your role ({role}) can read schedules{allows(role, "steward") ? ", with the values their runs start with" : ""}.
          Designers and admins add and change them.
        </p>
      )}
      {editing && (
        <ScheduleForm
          target={targetOf(editing)}
          schedule={editing}
          role={role}
          onClose={() => setEditing(null)}
          onSaved={(s) => {
            setEditing(null);
            actions.setError(null);
            actions.setNotice(`Schedule ${s.id} is saved.`);
            reload();
          }}
        />
      )}
    </>
  );
}

import { useEffect, useState } from "react";
import { allows, type AccessRole, type Schedule, type ScheduleTarget } from "../api";
import { href } from "../router";
import { docHref } from "../docs/links";
import { ScheduleForm, targetName } from "./ScheduleForm";
import { SchedulesTable, useScheduleActions, useSchedules } from "./SchedulesTable";
import "./schedules.css";

/** How often the schedules are read again while they are shown, for their last outcomes. */
const REFRESH_MS = 30_000;

/**
 * A pipeline's or flow's schedules, in the panel under its canvas: when each runs, its next run,
 * whether it is paused, who set it and the role its runs have, and what happened last time, linked
 * to the run. Stewards and above see the values its runs start with. Designers and admins add,
 * change, pause, resume and delete them.
 */
export function SchedulesSection({
  target,
  role,
  data,
  canAdd = true,
}: {
  target: ScheduleTarget;
  role: AccessRole;
  data: ReturnType<typeof useSchedules>;
  /** False while there is no saved version to run. */
  canAdd?: boolean;
}) {
  const designer = allows(role, "designer");
  const { schedules, error: loadError, reload } = data;
  const actions = useScheduleActions(reload);
  const [editing, setEditing] = useState<Schedule | "new" | null>(null);
  const what = "pipeline" in target ? "pipeline" : "flow";

  useEffect(() => {
    const t = window.setInterval(reload, REFRESH_MS);
    return () => window.clearInterval(t);
  }, [reload]);

  return (
    <div className="sc-section">
      <h3 className="visually-hidden">Schedules</h3>
      <div className="sc-section-head">
        <p className="small muted sc-intro">
          Runs of the latest saved version at set times, without anyone pressing Run now. Times are shown in your own time
          zone. <a href={href.schedules()}>Every schedule</a> · <a href={docHref("schedules")}>How schedules work</a>
        </p>
        {designer && (
          <button
            type="button"
            className="primary"
            onClick={() => setEditing("new")}
            disabled={!canAdd}
            aria-describedby={canAdd ? undefined : "sc-add-hint"}
          >
            Add a schedule
          </button>
        )}
      </div>
      {designer && !canAdd && (
        <p id="sc-add-hint" className="hint">
          Save the {what} first: a schedule runs its latest saved version.
        </p>
      )}
      <p role="status" className="notice" tabIndex={-1} ref={actions.noticeRef}>
        {actions.notice}
      </p>
      {(actions.error || loadError) && (
        <p role="alert" className="error">
          {actions.error ?? loadError}
        </p>
      )}
      {schedules === null && !loadError && <p role="status">Loading…</p>}
      {schedules?.length === 0 && (
        <p>
          No schedules. {designer ? `Choose Add a schedule to run this ${what} at set times.` : `Designers add them.`}
        </p>
      )}
      {schedules && schedules.length > 0 && (
        <SchedulesTable
          schedules={schedules}
          role={role}
          label={`Schedules of ${targetName(target)}`}
          busy={actions.busy}
          onEdit={designer ? (s) => void actions.open(s, setEditing) : undefined}
          onToggle={designer ? actions.toggle : undefined}
          onDelete={designer ? actions.remove : undefined}
        />
      )}
      {editing && (
        <ScheduleForm
          target={target}
          schedule={editing === "new" ? null : editing}
          role={role}
          onClose={() => setEditing(null)}
          onSaved={(s, added) => {
            setEditing(null);
            actions.setError(null);
            actions.setNotice(added ? `Schedule ${s.id} is added.` : `Schedule ${s.id} is saved.`);
            reload();
          }}
        />
      )}
    </div>
  );
}

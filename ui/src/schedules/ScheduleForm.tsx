import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import {
  allows,
  flowsApi,
  pipelinesApi,
  schedulesApi,
  type AccessRole,
  type FlowParameter,
  type Schedule,
  type SchedulePreview,
  type ScheduleTarget,
} from "../api";
import { ParameterFields } from "../flows/ParameterFields";
import { defaultText, runValues } from "../flows/parameters";
import {
  browserZone,
  MINUTE_STEPS,
  pipelineParameters,
  presetCron,
  readPreset,
  timeZones,
  valueText,
  whenText,
  type Preset,
  type PresetKind,
} from "./cron";

/** How long to wait after a change to the times before asking when they fall. */
const PREVIEW_DELAY_MS = 400;

const KIND_LABEL: Record<PresetKind, string> = {
  daily: "Every day at a time",
  weekdays: "Weekdays (Monday to Friday) at a time",
  hourly: "Every hour",
  minutes: "Every few minutes",
  monthly: "A day of each month",
  custom: "Something else (write the cron expression)",
};

/** What the form is for: the pipeline or flow, and the schedule when one is changed. */
export const targetOf = (s: Schedule): ScheduleTarget => (s.pipeline != null ? { pipeline: s.pipeline } : { flow: s.flow! });
export const targetName = (t: ScheduleTarget) => ("pipeline" in t ? t.pipeline : t.flow);

/** The parameters the latest version of a pipeline or flow declares. */
export async function declaredParameters(t: ScheduleTarget): Promise<FlowParameter[]> {
  if ("pipeline" in t) {
    const d = await pipelinesApi.get(t.pipeline);
    return d.latest ? pipelineParameters(d.latest.yaml) : [];
  }
  return (await flowsApi.get(t.flow)).latest?.parameters ?? [];
}

/**
 * Adding or changing a schedule, in a dialog (designers and admins). Presets fill the cron
 * expression for the common times; anything else is written as cron. As the times or the zone
 * change, the next five times are shown, or why the expression cannot be used. The values for the
 * latest version's parameters are typed as in the Run now dialog. The server checks everything
 * again when it is saved, and its message is shown here.
 */
export function ScheduleForm({
  target,
  schedule,
  role,
  onSaved,
  onClose,
}: {
  target: ScheduleTarget;
  /** The schedule to change; null to add one. */
  schedule: Schedule | null;
  role: AccessRole;
  onSaved: (s: Schedule, added: boolean) => void;
  onClose: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const name = targetName(target);
  const what = "pipeline" in target ? "pipeline" : "flow";
  const startCron = schedule?.cron ?? "0 2 * * *";
  const start = readPreset(startCron);
  const [kind, setKind] = useState<PresetKind>(start.kind);
  const [time, setTime] = useState("time" in start ? start.time : "02:00");
  const [minute, setMinute] = useState(start.kind === "hourly" ? start.minute : 0);
  const [every, setEvery] = useState(start.kind === "minutes" ? start.every : 15);
  const [day, setDay] = useState(start.kind === "monthly" ? start.day : 1);
  const [cron, setCron] = useState(startCron);
  const [zone, setZone] = useState(schedule?.zone ?? browserZone());
  const [runAs, setRunAs] = useState<"steward" | "designer">(schedule?.runAs ?? "steward");
  const [description, setDescription] = useState(schedule?.description ?? "");
  const [paused, setPaused] = useState(schedule ? !schedule.enabled : false);
  const [params, setParams] = useState<FlowParameter[] | null>(null);
  const [paramsError, setParamsError] = useState<string | null>(null);
  const [typed, setTyped] = useState<Record<string, string>>({});
  const [problems, setProblems] = useState<Record<string, string>>({});
  // The answer, with the expression and zone it was asked for, so an older one is never shown as this one's.
  const [preview, setPreview] = useState<{ asked: string; answer: SchedulePreview } | null>(null);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const zones = useMemo(() => timeZones(zone), [zone]);
  const designerOffered = allows(role, "designer");

  useEffect(() => {
    const d = dialog.current;
    if (d && !d.open) {
      if (typeof d.showModal === "function") d.showModal();
      else d.setAttribute("open", "");
    }
  }, []);

  // The latest version's parameters, with the schedule's values (or the defaults) typed in.
  useEffect(() => {
    let live = true;
    declaredParameters(target)
      .then((ps) => {
        if (!live) return;
        setParams(ps);
        const stored = schedule?.parameters ?? {};
        setTyped(Object.fromEntries(ps.map((p) => [p.name, p.name in stored ? valueText(stored[p.name]) : defaultText(p)])));
      })
      .catch((e: Error) => live && setParamsError(e.message));
    return () => {
      live = false;
    };
    // The target and schedule are fixed while the form is open.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Values the schedule holds for parameters the latest version no longer declares: left out on saving.
  const dropped = useMemo(() => {
    if (!params || !schedule?.parameters) return [];
    const names = new Set(params.map((p) => p.name));
    return Object.keys(schedule.parameters).filter((k) => !names.has(k));
  }, [params, schedule]);

  // When the times fall, a moment after the expression or zone stops changing; only the latest answer counts.
  useEffect(() => {
    if (!cron.trim()) {
      setPreview(null);
      setPreviewError(null);
      return;
    }
    const abort = new AbortController();
    const asked = `${cron.trim()}|${zone}`;
    setPreviewError(null);
    const t = window.setTimeout(() => {
      schedulesApi
        .preview(cron.trim(), zone, abort.signal)
        .then((p) => {
          setPreview({ asked, answer: p });
          setPreviewError(null);
        })
        .catch((e: Error) => {
          if (!abort.signal.aborted) setPreviewError(e.message);
        });
    }, PREVIEW_DELAY_MS);
    return () => {
      window.clearTimeout(t);
      abort.abort();
    };
  }, [cron, zone]);

  /**
   * Sets a preset's part and the expression it gives. A part that is not right yet (no time, the
   * 32nd) empties the expression, so the times shown and saved are never an earlier choice's.
   */
  const choose = (p: Preset) => {
    setKind(p.kind);
    if (p.kind === "custom") return;
    setCron(presetCron(p) ?? "");
  };
  const presetNow = (over: Partial<{ kind: PresetKind; time: string; minute: number; every: number; day: number }>): Preset => {
    const k = over.kind ?? kind;
    const v = { time: over.time ?? time, minute: over.minute ?? minute, every: over.every ?? every, day: over.day ?? day };
    switch (k) {
      case "daily":
      case "weekdays":
        return { kind: k, time: v.time };
      case "monthly":
        return { kind: k, day: v.day, time: v.time };
      case "hourly":
        return { kind: k, minute: v.minute };
      case "minutes":
        return { kind: k, every: v.every };
      default:
        return { kind: "custom" };
    }
  };
  /** Typing the expression chooses the preset it is, if any, with its parts. */
  const typeCron = (text: string) => {
    setCron(text);
    const p = readPreset(text);
    setKind(p.kind);
    if ("time" in p) setTime(p.time);
    if (p.kind === "hourly") setMinute(p.minute);
    if (p.kind === "minutes") setEvery(p.every);
    if (p.kind === "monthly") setDay(p.day);
  };

  const close = () => {
    const d = dialog.current;
    if (d?.open && typeof d.close === "function") d.close();
    onClose();
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (saving) return;
    setError(null);
    if (!cron.trim()) {
      setError(
        kind === "custom" ? "Give the times: choose a preset or write a cron expression." : "Finish the times: a part of them is not right yet.",
      );
      return;
    }
    if (params == null || paramsError) {
      // Saving now would replace the values the schedule holds with none.
      setError("The parameters could not be read, so the values cannot be saved. Close the form and try again.");
      return;
    }
    const { values, problems: found } = runValues(params ?? [], typed);
    setProblems(found);
    if (Object.keys(found).length > 0) {
      setError("Some values need changing: see the fields marked.");
      return;
    }
    setSaving(true);
    const body = {
      ...("pipeline" in target ? { pipeline: target.pipeline } : { flow: target.flow }),
      cron: cron.trim(),
      zone,
      parameters: values,
      runAs,
      enabled: !paused,
      description: description.trim() || null,
    };
    try {
      const saved = schedule ? await schedulesApi.replace(schedule.id, body) : await schedulesApi.create(body);
      const d = dialog.current;
      if (d?.open && typeof d.close === "function") d.close();
      onSaved(saved, schedule == null);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const shownPreview = preview && preview.asked === `${cron.trim()}|${zone}` ? preview.answer : null;
  const id = "sf";
  const heading = schedule ? `Change schedule ${schedule.id}` : `Add a schedule for ${name}`;

  return (
    <dialog
      ref={dialog}
      aria-labelledby={`${id}-h`}
      className="dialog sf-dialog"
      onCancel={(e) => {
        e.preventDefault();
        close();
      }}
    >
      <form onSubmit={submit} noValidate>
        <h2 id={`${id}-h`}>{heading}</h2>
        <p className="hint">
          Each run is of the {what}&apos;s latest saved version, checked as Run now checks it. It is recorded as asked for
          by this schedule, in the name of whoever saves it: you.
        </p>

        <fieldset className="sf-when">
          <legend>When</legend>
          <div className="field">
            <label htmlFor={`${id}-repeat`}>Repeats</label>
            <select
              id={`${id}-repeat`}
              value={kind}
              onChange={(e) => {
                const k = e.target.value as PresetKind;
                choose(presetNow({ kind: k }));
              }}
            >
              {(Object.keys(KIND_LABEL) as PresetKind[]).map((k) => (
                <option key={k} value={k}>
                  {KIND_LABEL[k]}
                </option>
              ))}
            </select>
          </div>
          {kind === "monthly" && (
            <div className="field">
              <label htmlFor={`${id}-day`}>Day of the month</label>
              <input
                id={`${id}-day`}
                type="number"
                min={1}
                max={31}
                value={day}
                aria-describedby={`${id}-day-hint`}
                onChange={(e) => {
                  const d = Number(e.target.value);
                  setDay(d);
                  choose(presetNow({ day: d }));
                }}
              />
              <p id={`${id}-day-hint`} className="hint">
                1 to 31. A month without that day is passed over: the 31st runs in seven months of the year.
              </p>
            </div>
          )}
          {(kind === "daily" || kind === "weekdays" || kind === "monthly") && (
            <div className="field">
              <label htmlFor={`${id}-time`}>At (24-hour clock, in the time zone below)</label>
              <input
                id={`${id}-time`}
                type="time"
                value={time}
                required
                onChange={(e) => {
                  setTime(e.target.value);
                  choose(presetNow({ time: e.target.value }));
                }}
              />
            </div>
          )}
          {kind === "hourly" && (
            <div className="field">
              <label htmlFor={`${id}-minute`}>Minutes past the hour</label>
              <input
                id={`${id}-minute`}
                type="number"
                min={0}
                max={59}
                value={minute}
                onChange={(e) => {
                  const m = Number(e.target.value);
                  setMinute(m);
                  choose(presetNow({ minute: m }));
                }}
              />
            </div>
          )}
          {kind === "minutes" && (
            <div className="field">
              <label htmlFor={`${id}-every`}>Every</label>
              <select
                id={`${id}-every`}
                value={every}
                aria-describedby={`${id}-every-hint`}
                onChange={(e) => {
                  const n = Number(e.target.value);
                  setEvery(n);
                  choose(presetNow({ every: n }));
                }}
              >
                {MINUTE_STEPS.map((n) => (
                  <option key={n} value={n}>
                    {n === 1 ? "1 minute" : `${n} minutes`}
                  </option>
                ))}
              </select>
              <p id={`${id}-every-hint`} className="hint">
                Counted from each hour, so the gaps stay even. A run still going when the next is due makes that one skip.
              </p>
            </div>
          )}
          <div className="field">
            <label htmlFor={`${id}-cron`}>Cron expression</label>
            <input
              id={`${id}-cron`}
              className="mono"
              value={cron}
              autoComplete="off"
              spellCheck={false}
              aria-describedby={`${id}-cron-hint`}
              onChange={(e) => typeCron(e.target.value)}
            />
            <p id={`${id}-cron-hint`} className="hint">
              Minute, hour, day of the month, month, day of the week: <span className="mono">30 6 * * MON-FRI</span> is
              06:30 on weekdays. A preset fills this in.
            </p>
          </div>
          <div className="field">
            <label htmlFor={`${id}-zone`}>Time zone</label>
            <select id={`${id}-zone`} value={zone} aria-describedby={`${id}-zone-hint`} onChange={(e) => setZone(e.target.value)}>
              {zones.map((z) => (
                <option key={z} value={z}>
                  {z}
                  {z === browserZone() ? " (yours)" : ""}
                </option>
              ))}
            </select>
            <p id={`${id}-zone-hint`} className="hint">
              The times are in this zone, through summer and winter. Type the first letters to move through the list.
            </p>
          </div>
          <div className="sf-preview" aria-live="polite">
            <h3 className="sf-preview-h">Next five runs</h3>
            {!cron.trim() ? (
              <p className="small muted">
                {kind === "custom"
                  ? "Choose a preset or write an expression to see when it runs."
                  : "Finish the times above to see when it runs."}
              </p>
            ) : previewError ? (
              <p className="small pl-bad">Could not work out the times: {previewError}</p>
            ) : !shownPreview ? (
              <p className="small muted">Working out the times…</p>
            ) : !shownPreview.valid ? (
              <p className="small pl-bad">
                <strong>Cannot be used:</strong> {shownPreview.problem}
              </p>
            ) : (
              <ol className="sf-next small">
                {shownPreview.next.map((t) => (
                  <li key={t}>{whenText(t, shownPreview.zone)}</li>
                ))}
              </ol>
            )}
          </div>
        </fieldset>

        <fieldset>
          <legend>Values</legend>
          {paramsError ? (
            <p role="alert" className="error">
              The {what}&apos;s parameters could not be read: {paramsError}
            </p>
          ) : params == null ? (
            <p role="status" className="small muted">
              Reading the {what}&apos;s parameters…
            </p>
          ) : params.length === 0 ? (
            <p className="small muted">The latest version declares no parameters.</p>
          ) : (
            <>
              <p className="hint">
                A field left as its default is not saved, so each run takes the default of the version then latest. Lists
                are separated by commas.
              </p>
              <ParameterFields
                idBase={`${id}-p`}
                params={params}
                typed={typed}
                problems={problems}
                onChange={(p, v) => setTyped((t) => ({ ...t, [p]: v }))}
              />
            </>
          )}
          {dropped.length > 0 && (
            <p className="warning small">
              This schedule has values for {dropped.join(", ")}, which the latest version no longer declares. Saving leaves
              them out.
            </p>
          )}
        </fieldset>

        <div className="field">
          <label htmlFor={`${id}-runas`}>Runs as</label>
          <select
            id={`${id}-runas`}
            value={runAs}
            aria-describedby={`${id}-runas-hint`}
            onChange={(e) => setRunAs(e.target.value as "steward" | "designer")}
          >
            <option value="steward">Steward</option>
            {(designerOffered || runAs === "designer") && <option value="designer">Designer</option>}
          </select>
          <p id={`${id}-runas-hint`} className="hint">
            The role its runs have. Steward is enough to run pipelines and flows. Never admin, and never above your own.
          </p>
        </div>
        <div className="field">
          <label htmlFor={`${id}-description`}>Description (optional)</label>
          <input
            id={`${id}-description`}
            value={description}
            maxLength={200}
            placeholder="For example: the weekday billing load"
            onChange={(e) => setDescription(e.target.value)}
          />
        </div>
        <div className="field sf-check">
          <input id={`${id}-paused`} type="checkbox" checked={paused} onChange={(e) => setPaused(e.target.checked)} />
          <label htmlFor={`${id}-paused`}>Paused: it does not run until it is resumed</label>
        </div>

        {error && (
          <p role="alert" className="error">
            {error}
          </p>
        )}
        <div className="buttons">
          <button type="submit" className="primary" disabled={saving || params == null || paramsError != null}>
            {saving ? "Saving…" : schedule ? "Save changes" : "Add schedule"}
          </button>
          <button type="button" onClick={close}>
            Cancel
          </button>
        </div>
      </form>
    </dialog>
  );
}

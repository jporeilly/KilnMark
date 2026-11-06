-- Schedules (ADR 0046, docs/design/schedules.md): pipelines and flows that run at set times. A
-- schedule is a row here, not part of the YAML: who a scheduled run acts as is the decision of the
-- designer or admin who sets it, and changing a time must not make a new version. Refers only to
-- V12 (pipeline, pipeline_run) and V20 (flow, flow_run).

SET search_path = kilnmark, public;

-- One schedule of one pipeline or one flow; a pipeline or flow may have several. Deleting the
-- pipeline or flow deletes its schedules. cron is five fields read in zone (an IANA name);
-- parameters are the values each run starts with, as given (defaults are filled in at each run).
-- run_as is the role the run has, never admin; the run is requested by
-- 'schedule <id> (set by <updated_by>)'. next_at is when it fires next, null while it is paused or
-- when it never fires again. last_* say what happened the last time it was due: last_outcome is a
-- few words a person reads (started, skipped, missed or failed, with why), never values.
CREATE TABLE schedule (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pipeline           text REFERENCES pipeline (name) ON DELETE CASCADE,
    flow               text REFERENCES flow (name) ON DELETE CASCADE,
    cron               text NOT NULL CHECK (length(cron) BETWEEN 1 AND 200),
    zone               text NOT NULL DEFAULT 'UTC' CHECK (length(zone) BETWEEN 1 AND 64),
    parameters         jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(parameters) = 'object'),
    run_as             text NOT NULL DEFAULT 'steward' CHECK (run_as IN ('steward', 'designer')),
    enabled            boolean NOT NULL DEFAULT true,
    description        text CHECK (description IS NULL OR length(description) <= 200),
    created_by         text NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_by         text NOT NULL,
    updated_at         timestamptz NOT NULL DEFAULT now(),
    next_at            timestamptz,
    last_at            timestamptz,
    last_outcome       text CHECK (last_outcome IS NULL OR length(last_outcome) <= 500),
    -- The run the last fire started. Foreign keys, set to null when the run is deleted, so a link
    -- never leads to a run that is gone; the table is small, so they need no index of their own.
    last_pipeline_run  bigint REFERENCES pipeline_run (id) ON DELETE SET NULL,
    last_flow_run      bigint REFERENCES flow_run (id) ON DELETE SET NULL,
    CONSTRAINT schedule_one_target CHECK ((pipeline IS NULL) <> (flow IS NULL))
);
CREATE INDEX schedule_pipeline_idx ON schedule (pipeline) WHERE pipeline IS NOT NULL;
CREATE INDEX schedule_flow_idx ON schedule (flow) WHERE flow IS NOT NULL;
-- What the scheduler reads every poll: the schedules that are due.
CREATE INDEX schedule_due_idx ON schedule (next_at) WHERE enabled;

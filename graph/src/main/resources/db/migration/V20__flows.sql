-- Flows (docs/design/flows.md, ADR 0026): flow steps joined by paths, which run saved pipelines.
-- Saved as YAML with every version, run by a worker, and every run recorded step by step.
-- Refers only to V1 (install_info) and V12 (pipeline_run); a flow run is a job of kind 'flow'
-- (V9's job_kind_check takes any kind).

SET search_path = kilnmark, public;

-- A flow: its fixed name (used in URLs and runs) and what people see. latest_version is the number
-- of its newest saved version, and the value If-Match carries when saving another.
CREATE TABLE flow (
    name            text PRIMARY KEY CHECK (name ~ '^[a-z][a-z0-9-]{0,62}$'),
    display_name    text NOT NULL CHECK (length(display_name) BETWEEN 1 AND 200),
    description     text CHECK (description IS NULL OR length(description) <= 2000),
    latest_version  integer NOT NULL DEFAULT 0 CHECK (latest_version >= 0),
    created_by      text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      text NOT NULL,
    updated_at      timestamptz NOT NULL DEFAULT now()
);

-- Every saved version, never changed afterwards. An invalid version can be saved as a draft, with
-- its problems, but not run.
CREATE TABLE flow_version (
    flow        text NOT NULL REFERENCES flow (name) ON DELETE CASCADE,
    n           integer NOT NULL CHECK (n >= 1),
    yaml        text NOT NULL,
    message     text CHECK (message IS NULL OR length(message) <= 500),
    author      text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    valid       boolean NOT NULL,
    problems    jsonb NOT NULL DEFAULT '[]',   -- [{line, column, step, message}]
    PRIMARY KEY (flow, n)
);

-- One run of one version, queued as a job of kind 'flow' and decided by a worker. requested_role
-- is the role of whoever asked: the pipelines the flow runs run as that person, with that role.
CREATE TABLE flow_run (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    flow              text NOT NULL,
    version           integer NOT NULL,
    status            text NOT NULL DEFAULT 'queued'
                      CHECK (status IN ('queued', 'running', 'succeeded', 'failed', 'cancelled')),
    requested_by      text NOT NULL,
    requested_role    text NOT NULL CHECK (requested_role IN ('viewer', 'steward', 'admin')),
    requested_at      timestamptz NOT NULL DEFAULT now(),
    started_at        timestamptz,
    finished_at       timestamptz,
    cancel_requested  boolean NOT NULL DEFAULT false,
    error             text,                    -- why it failed or was stopped, in words; never row values
    parameters        jsonb NOT NULL DEFAULT '{}',   -- the values the run was started with, defaults filled in
    FOREIGN KEY (flow, version) REFERENCES flow_version (flow, n) ON DELETE CASCADE
);
CREATE INDEX flow_run_flow_idx ON flow_run (flow, id DESC);
-- One run of a flow at a time, as for pipelines.
CREATE UNIQUE INDEX flow_run_active_idx ON flow_run (flow) WHERE status IN ('queued', 'running');

-- Each flow step's part in a run. A step that never ran is recorded as skipped when the run ends.
CREATE TABLE flow_step_run (
    run_id           bigint NOT NULL REFERENCES flow_run (id) ON DELETE CASCADE,
    step_id          text NOT NULL,
    type             text NOT NULL,
    position         integer NOT NULL DEFAULT 0,        -- the step's place in the flow's order
    status           text NOT NULL
                     CHECK (status IN ('running', 'succeeded', 'failed', 'cancelled', 'skipped')),
    started_at       timestamptz,
    finished_at      timestamptz,
    pipeline_run_id  bigint,                            -- the run it started, for run-pipeline steps
    message          text,                              -- why it failed, in words; never row values
    -- what the step reported, for conditions: {score, quarantined_share, rows_written, duration_s}
    reported         jsonb NOT NULL DEFAULT '{}',
    PRIMARY KEY (run_id, step_id)
);

-- One job per flow run, found by its run (as scans' jobs are, V4).
CREATE UNIQUE INDEX job_flow_run_idx ON job (((payload ->> 'flowRunId')::bigint)) WHERE kind = 'flow';

-- A pipeline run started by a flow says which, so run pages link both ways and a restarted worker
-- finds the run a step had already started instead of starting another.
ALTER TABLE pipeline_run
    ADD COLUMN flow_run_id  bigint REFERENCES flow_run (id) ON DELETE SET NULL,
    ADD COLUMN flow_step    text;
CREATE UNIQUE INDEX pipeline_run_flow_step_idx ON pipeline_run (flow_run_id, flow_step)
    WHERE flow_run_id IS NOT NULL;

INSERT INTO install_info (key, value) VALUES ('schema_flows', '20')
ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now();

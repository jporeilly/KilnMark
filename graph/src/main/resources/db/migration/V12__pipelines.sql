-- Pipelines (docs/design/pipelines.md, ADR 0022): pipelines saved as YAML with every version, their
-- runs and each step's part in a run, and the asset lineage their writes record.
-- Applies after V10 and V11 (0.0.3) or without them: it refers only to tables from V1 (install_info),
-- V2 and V4.

SET search_path = kilnmark, public;

-- A pipeline: its fixed name (used in URLs and runs) and what people see. latest_version is the
-- number of its newest saved version, and the value If-Match carries when saving another.
CREATE TABLE pipeline (
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
CREATE TABLE pipeline_version (
    pipeline    text NOT NULL REFERENCES pipeline (name) ON DELETE CASCADE,
    n           integer NOT NULL CHECK (n >= 1),
    yaml        text NOT NULL,
    message     text CHECK (message IS NULL OR length(message) <= 500),
    author      text NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    valid       boolean NOT NULL,
    problems    jsonb NOT NULL DEFAULT '[]',   -- [{line, column, step, message}]
    PRIMARY KEY (pipeline, n)
);

-- One run of one version, queued as a job of kind 'pipeline' and run by a worker.
-- requested_role is the role of whoever asked, checked again by write steps when the run starts.
CREATE TABLE pipeline_run (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pipeline          text NOT NULL,
    version           integer NOT NULL,
    status            text NOT NULL DEFAULT 'queued'
                      CHECK (status IN ('queued', 'running', 'succeeded', 'failed', 'cancelled')),
    requested_by      text NOT NULL,
    requested_role    text NOT NULL CHECK (requested_role IN ('viewer', 'steward', 'admin')),
    requested_at      timestamptz NOT NULL DEFAULT now(),
    started_at        timestamptz,
    finished_at       timestamptz,
    cancel_requested  boolean NOT NULL DEFAULT false,
    error             text,
    result            jsonb,                    -- {rowsWritten, written: [{step, urn, rows}], read: [urn]}
    FOREIGN KEY (pipeline, version) REFERENCES pipeline_version (pipeline, n) ON DELETE CASCADE
);
CREATE INDEX pipeline_run_pipeline_idx ON pipeline_run (pipeline, id DESC);
CREATE INDEX pipeline_run_active_idx ON pipeline_run (status) WHERE status IN ('queued', 'running');

-- Each step's part in a run: rows in and out, per port, peak memory, time and its error.
CREATE TABLE pipeline_step_run (
    run_id       bigint NOT NULL REFERENCES pipeline_run (id) ON DELETE CASCADE,
    step_id      text NOT NULL,
    type         text NOT NULL,
    position     integer NOT NULL DEFAULT 0,  -- the step's place in the run order
    rows_in      bigint,
    rows_out     bigint,
    port_rows    jsonb NOT NULL DEFAULT '{}', -- {"in": {"main": 10}, "out": {"out": 8, "rejected": 2}}
    peak_memory  bigint,                      -- bytes
    duration_ms  bigint,
    error        text,
    PRIMARY KEY (run_id, step_id)
);

-- Asset lineage recorded by pipeline writes: the written asset came from these read assets. Kept
-- when the pipeline or its runs are deleted, so the catalogue keeps where data came from.
CREATE TABLE pipeline_lineage (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pipeline    text NOT NULL,
    run_id      bigint REFERENCES pipeline_run (id) ON DELETE SET NULL,
    step_id     text NOT NULL,
    input_urn   text NOT NULL,
    output_urn  text NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX pipeline_lineage_output_idx ON pipeline_lineage (output_urn, recorded_at DESC);
CREATE INDEX pipeline_lineage_input_idx ON pipeline_lineage (input_urn, recorded_at DESC);
CREATE INDEX pipeline_lineage_run_idx ON pipeline_lineage (run_id);

-- A pipeline's write registers the written asset as the scan does, under a run of kind 'pipeline'
-- (finished when it is recorded, so it never counts as a source's scan in progress).
ALTER TABLE run DROP CONSTRAINT IF EXISTS run_kind_check;
ALTER TABLE run ADD CONSTRAINT run_kind_check CHECK (kind IN ('scan', 'pipeline'));

-- Sources are read-only to pipelines unless an admin sets allow_writing (kept with the source's
-- settings; absent means false). Nothing to migrate: no source has it yet.

INSERT INTO install_info (key, value) VALUES ('schema_pipelines', '12')
ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now();

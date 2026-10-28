-- Seeing inside a pipeline run (docs/design/pipelines.md sections 1 and 5, docs/guide/pipelines.md
-- "Reading a run"): when each step started, had its first row, handed on its last row and ended;
-- the run's log, as it went; and where its rows are while it runs. Refers only to V1 (install_info)
-- and V12 (pipeline_run, pipeline_step_run).

SET search_path = kilnmark, public;

-- Each step's times, as the engine recorded them; null for runs before V19, and for a step that never
-- started. duration_ms (V12) is now filled: finished_at - started_at.
ALTER TABLE pipeline_step_run
    ADD COLUMN started_at    timestamptz,
    ADD COLUMN first_row_at  timestamptz,  -- its first row in; for a step with no input, its first row out
    ADD COLUMN last_row_at   timestamptz,  -- its last row out; for a step with no output, its last row in
    ADD COLUMN finished_at   timestamptz;

-- Where the rows are, while the run goes: the latest snapshot, written by the worker at most every
-- two seconds (the API may run in another container, so it goes through the database), and the last
-- one kept when the run ends. Counts, states and times only, never values from the data:
-- {at, slowest, steps: [{step, state, rowsIn, rowsOut, rowsPerSecond, startedAt, firstRowAt, lastRowAt,
--  finishedAt}], edges: [{from, fromPort, to, toPort, rows, queued, capacity, full, empty}]}
ALTER TABLE pipeline_run
    ADD COLUMN progress     jsonb,
    ADD COLUMN progress_at  timestamptz;

-- The run's log: what happened, in order. text is the server's own words, shown to everyone who may
-- see the run; detail, an engine's or database's message, may quote values from the rows and is held
-- back as the run's other messages are ("Messages from the data"). At most 10,000 events a run, then
-- one saying the log was cut short (seq 10,001). Deleted with the run.
CREATE TABLE pipeline_run_event (
    run_id   bigint NOT NULL REFERENCES pipeline_run (id) ON DELETE CASCADE,
    seq      integer NOT NULL CHECK (seq BETWEEN 1 AND 10001),
    at       timestamptz NOT NULL,
    step_id  text,                       -- null for the run as a whole
    level    text NOT NULL CHECK (level IN ('info', 'warn', 'error')),
    kind     text NOT NULL CHECK (kind ~ '^[a-z][a-z_]{0,39}$'),
    text     text NOT NULL CHECK (length(text) <= 2000),
    detail   text CHECK (detail IS NULL OR length(detail) <= 8000),
    PRIMARY KEY (run_id, seq)
);

INSERT INTO install_info (key, value) VALUES ('schema_pipeline_insight', '19')
ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now();

-- Run retries, carrying on from a failure, and owners (ADR 0053, docs/design/pipelines.md and
-- docs/design/flows.md). Refers only to V12 (pipeline, pipeline_run), V20 (flow, flow_run) and V35.

SET search_path = kilnmark, public;

-- A run's tries: run_try counts them from 0; tries is how many it may have by itself (1 + its
-- retries, after the server's cap; 1 for a run a flow node starts; the try's own number + 1 after Run
-- again from the failure, which is not tried again by itself). While a run waits for its next
-- try it is queued again, and retry_at says when that try starts. warned_at is when its owners were
-- told it was taking longer than expected (once a run).
ALTER TABLE pipeline_run
    ADD COLUMN run_try    smallint NOT NULL DEFAULT 0 CHECK (run_try BETWEEN 0 AND 50),
    ADD COLUMN tries      smallint NOT NULL DEFAULT 1 CHECK (tries BETWEEN 1 AND 51),
    ADD COLUMN retry_at   timestamptz,
    ADD COLUMN warned_at  timestamptz,
    -- What ended a failed run, so a flow knows whether running its node again could change anything:
    -- refused (it could not be set up), gate (a quality gate stopped it), timed_out, lost, error.
    ADD COLUMN failed_as  text CHECK (failed_as IS NULL OR failed_as IN ('refused', 'gate', 'timed_out', 'lost',
        'error', 'cancelled'));
ALTER TABLE flow_run
    ADD COLUMN run_try    smallint NOT NULL DEFAULT 0 CHECK (run_try BETWEEN 0 AND 50),
    ADD COLUMN tries      smallint NOT NULL DEFAULT 1 CHECK (tries BETWEEN 1 AND 51),
    ADD COLUMN retry_at   timestamptz,
    ADD COLUMN warned_at  timestamptz;

-- Each try of a run that has ended: how it ended (lost: its worker stopped or lost its
-- connection), when, why in Kilnmark's words (a pipeline's error is held back from viewers as the
-- run's is), what came next (again: tried again; else why not), and who asked for it by hand.
CREATE TABLE pipeline_run_try (
    run_id       bigint NOT NULL REFERENCES pipeline_run (id) ON DELETE CASCADE,
    n            smallint NOT NULL CHECK (n >= 0),
    status       text NOT NULL CHECK (status IN ('succeeded', 'failed', 'cancelled', 'lost')),
    started_at   timestamptz,
    finished_at  timestamptz NOT NULL DEFAULT now(),
    error        text,
    next         text CHECK (next IS NULL OR length(next) <= 500),
    asked_by     text,
    PRIMARY KEY (run_id, n)
);
CREATE TABLE flow_run_try (
    run_id       bigint NOT NULL REFERENCES flow_run (id) ON DELETE CASCADE,
    n            smallint NOT NULL CHECK (n >= 0),
    status       text NOT NULL CHECK (status IN ('succeeded', 'failed', 'cancelled', 'lost')),
    started_at   timestamptz,
    finished_at  timestamptz NOT NULL DEFAULT now(),
    error        text,
    next         text CHECK (next IS NULL OR length(next) <= 500),
    asked_by     text,
    PRIMARY KEY (run_id, n)
);

-- Owners: who answers for a pipeline or a flow, told when its runs fail after their last try,
-- recover, or take long. Kept outside the YAML, so changing them makes no new version. Each entry is a
-- person's sign-in name or a team address; they give no rights. By default whoever first saved it.
ALTER TABLE pipeline ADD COLUMN owners text[] NOT NULL DEFAULT '{}' CHECK (cardinality(owners) <= 10);
ALTER TABLE flow ADD COLUMN owners text[] NOT NULL DEFAULT '{}' CHECK (cardinality(owners) <= 10);
UPDATE pipeline SET owners = ARRAY[created_by]
    WHERE created_by <> 'anonymous' AND created_by NOT LIKE 'server%' AND length(created_by) BETWEEN 1 AND 200;
UPDATE flow SET owners = ARRAY[created_by]
    WHERE created_by <> 'anonymous' AND created_by NOT LIKE 'server%' AND length(created_by) BETWEEN 1 AND 200;

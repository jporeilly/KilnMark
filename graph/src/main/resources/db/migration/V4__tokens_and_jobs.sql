-- API tokens for machines and the web UI, and the job queue workers pull scans from.

SET search_path = kilnmark, public;

-- A token is km_ followed by random base64url text. Only its SHA-256 is kept: the token itself is
-- shown once, when it is created, and cannot be read back.
CREATE TABLE api_token (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name          text NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
    role          text NOT NULL CHECK (role IN ('viewer', 'steward', 'admin')),
    hash          bytea NOT NULL UNIQUE CHECK (length(hash) = 32),
    created_by    text NOT NULL,      -- the token that created it, 'environment' or 'first-start'
    created_at    timestamptz NOT NULL DEFAULT now(),
    expires_at    timestamptz,        -- null: never expires
    last_used_at  timestamptz,        -- updated at most once a minute
    revoked_at    timestamptz,
    revoked_reason text               -- who revoked it, or 'environment-changed' (undone if the value returns)
);

-- Work for the worker role. A worker claims a job with SELECT ... FOR UPDATE SKIP LOCKED, marks it
-- running and runs it outside the transaction, refreshing heartbeat_at while it works. A running
-- job whose heartbeat stops is requeued, or failed once max_attempts is reached.
CREATE TABLE job (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind          text NOT NULL CHECK (kind IN ('scan')),
    payload       jsonb NOT NULL DEFAULT '{}',
    status        text NOT NULL DEFAULT 'queued' CHECK (status IN ('queued', 'running', 'done', 'failed')),
    run_after     timestamptz NOT NULL DEFAULT now(),
    attempts      integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts  integer NOT NULL DEFAULT 3 CHECK (max_attempts >= 1),
    locked_by     text,
    locked_at     timestamptz,
    heartbeat_at  timestamptz,
    last_error    text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    finished_at   timestamptz
);
CREATE INDEX job_ready_idx ON job (run_after, id) WHERE status = 'queued';
CREATE INDEX job_running_idx ON job (heartbeat_at) WHERE status = 'running';
-- One job per scan run, so a run cannot be queued twice.
CREATE UNIQUE INDEX job_scan_run_idx ON job (((payload ->> 'runId')::bigint)) WHERE kind = 'scan';

-- Before the queue, scans ran inside the server process. Runs it left waiting or running have no
-- job and would never finish, which also keeps the UI's scan button disabled.
UPDATE run SET status = 'failed', finished_at = now(),
       detail = 'Stopped when Kilnmark was upgraded to the job queue. Start the scan again.'
WHERE status IN ('queued', 'running');

-- At most one waiting or running scan per source: asking again returns the one in progress, and
-- two scans of a source never overlap (an earlier one would mark the later one's assets removed).
CREATE UNIQUE INDEX run_one_active_per_source_idx ON run (source_id) WHERE status IN ('queued', 'running');

INSERT INTO install_info (key, value) VALUES ('schema_catalog', '4')
ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now();

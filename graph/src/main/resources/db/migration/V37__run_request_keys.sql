-- Request keys for starting runs (ADR 0047, docs/guide/external-schedulers.md). A scheduler that
-- retries POST /api/v1/pipelines/{name}/runs or /flows/{name}/runs sends the same Idempotency-Key,
-- and gets the run its first request started instead of a second one. A key belongs to one caller
-- (a token, or a person signed in with the identity provider) and to one pipeline or flow, for 24
-- hours; a clean-up on processes with the scheduler role deletes older keys. A key that could not
-- start a run is never stored. Refers to V12 (pipeline_run) and V20 (flow_run): deleting a run
-- deletes its key, and a key with no run left starts a new one.
--
-- This must merge after V36 (the built-in scheduler): Flyway refuses a migration older than the
-- last applied one.

SET search_path = kilnmark, public;

CREATE TABLE run_request_key (
    caller           text NOT NULL,       -- 'token:<id>', or 'user:<name>' for a provider sign-in
    request_key      text NOT NULL CHECK (length(request_key) BETWEEN 1 AND 200),
    kind             text NOT NULL CHECK (kind IN ('pipeline', 'flow')),
    target           text NOT NULL,       -- the pipeline's or flow's name
    pipeline_run_id  bigint REFERENCES pipeline_run (id) ON DELETE CASCADE,
    flow_run_id      bigint REFERENCES flow_run (id) ON DELETE CASCADE,
    created_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (caller, request_key),
    -- the run is set in the transaction that stores the key, so a stored key always has its one run
    CHECK (num_nonnulls(pipeline_run_id, flow_run_id) <= 1)
);
CREATE INDEX run_request_key_created_idx ON run_request_key (created_at);
CREATE INDEX run_request_key_pipeline_run_idx ON run_request_key (pipeline_run_id) WHERE pipeline_run_id IS NOT NULL;
CREATE INDEX run_request_key_flow_run_idx ON run_request_key (flow_run_id) WHERE flow_run_id IS NOT NULL;

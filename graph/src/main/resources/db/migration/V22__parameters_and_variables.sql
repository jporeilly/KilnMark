-- Parameters and server variables (docs/design/pipelines.md, "Parameters and server variables").
-- A server variable is a value every pipeline and flow on this server may read as vars.name: a
-- schema, a base path, a region code. Never a secret: credentials stay in sources. Every change is
-- kept, also after a variable is deleted. Runs record the values they started with.
-- Refers only to V12 (pipeline_run) and V20 (flow_run).

SET search_path = kilnmark, public;

CREATE TABLE variable (
    name         text PRIMARY KEY CHECK (name ~ '^[a-z][a-z0-9_]{0,63}$'),
    type         text NOT NULL CHECK (type IN ('text', 'number', 'boolean')),
    value        jsonb NOT NULL,
    description  text CHECK (description IS NULL OR length(description) <= 500),
    updated_by   text NOT NULL,
    updated_at   timestamptz NOT NULL DEFAULT now()
);

-- What each change set: the value before and after (null when created or deleted), by whom and when.
CREATE TABLE variable_change (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        text NOT NULL,
    old_value   jsonb,
    new_value   jsonb,
    changed_by  text NOT NULL,
    changed_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX variable_change_name_idx ON variable_change (name, changed_at DESC);

-- The values a run started with: its parameters (defaults filled in) and the server's variables as
-- they were when it was queued, which are what the run reads, however they change afterwards.
ALTER TABLE pipeline_run
    ADD COLUMN parameters jsonb NOT NULL DEFAULT '{}',
    ADD COLUMN variables  jsonb NOT NULL DEFAULT '{}';
ALTER TABLE flow_run
    ADD COLUMN variables  jsonb NOT NULL DEFAULT '{}';

-- Data quality: profile versions, samples, accepted patterns, rules and their results, measures,
-- scores, drift events and the steward's issues (docs/design/data-quality.md, ADR 0020).
-- Written to apply whether or not V5 to V8 have run: it refers only to tables from V2 and V4.
--
-- Provenance: profile versions, rules and results say which catalogue they came from (origin,
-- 'kilnmark' for our own), when it was observed and how (method), so profiles and checks imported
-- from another catalogue later sit beside our own and compare directly.

SET search_path = kilnmark, public;

-- Columns a steward marks sensitive are masked in samples and examples for anyone who may not unmask.
ALTER TABLE field ADD COLUMN IF NOT EXISTS sensitive boolean NOT NULL DEFAULT false;

-- One profile version per asset per scan (or imported baseline). Column statistics of our own
-- versions are the rows of `profile` for the same run; imported versions carry theirs in `columns`.
CREATE TABLE profile_version (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    asset_urn           text NOT NULL REFERENCES asset (urn) ON DELETE CASCADE,
    run_id              bigint REFERENCES run (id) ON DELETE SET NULL,
    observed_at         timestamptz NOT NULL,
    row_count           bigint,
    row_count_complete  boolean,
    estimate            boolean NOT NULL DEFAULT false,   -- profiled from a sample of the table
    sampled_rows        bigint,
    sample_percent      double precision,
    estimated_rows      bigint,                           -- the database's own estimate, when sampled
    fields              jsonb NOT NULL DEFAULT '[]',      -- [{name, position, kind, sourceType, nullable}]
    columns             jsonb,                            -- imported versions only: {field: stats}
    origin              text NOT NULL DEFAULT 'kilnmark', -- the catalogue it came from
    origin_ref          text,                             -- its identifier in that catalogue
    method              text NOT NULL DEFAULT 'scan' CHECK (method ~ '^[a-z][a-z0-9-]{0,31}$'),
    is_baseline         boolean NOT NULL DEFAULT false,
    analysed_at         timestamptz,                      -- when measures, drift and scores were worked out
    UNIQUE (asset_urn, run_id)
);
CREATE INDEX profile_version_asset_idx ON profile_version (asset_urn, observed_at DESC);
CREATE INDEX profile_version_run_idx ON profile_version (run_id);

-- The latest sample of whole rows and the example values per value shape. Replaced on every scan,
-- deleted with the asset, and never written when the source's keep_samples is off.
CREATE TABLE sample (
    asset_urn   text PRIMARY KEY REFERENCES asset (urn) ON DELETE CASCADE,
    run_id      bigint REFERENCES run (id) ON DELETE SET NULL,
    method      text NOT NULL CHECK (method IN ('key', 'row', 'reservoir', 'none')),
    key_fields  text[] NOT NULL DEFAULT '{}',
    columns     jsonb NOT NULL DEFAULT '[]',
    rows        jsonb NOT NULL DEFAULT '[]',
    seen        bigint,
    examples    jsonb NOT NULL DEFAULT '{}',   -- {field: {shape: [values], "~other": [values]}}
    taken_at    timestamptz NOT NULL
);

CREATE TABLE quality_rule (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    asset_urn       text NOT NULL REFERENCES asset (urn) ON DELETE CASCADE,
    field_name      text,
    name            text NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    type            text NOT NULL CHECK (type IN ('not_null', 'unique', 'allowed_values', 'matches_pattern',
                        'in_range', 'freshness', 'row_count', 'row_count_change', 'references', 'custom_sql')),
    dimension       text NOT NULL CHECK (dimension IN ('completeness', 'uniqueness', 'validity', 'consistency',
                        'accuracy', 'timeliness', 'volume', 'integrity')),
    params          jsonb NOT NULL DEFAULT '{}',
    threshold_unit  text NOT NULL DEFAULT 'share' CHECK (threshold_unit IN ('share', 'count')),
    warn_above      double precision CHECK (warn_above >= 0),
    fail_above      double precision NOT NULL DEFAULT 0 CHECK (fail_above >= 0),
    actions         jsonb NOT NULL DEFAULT '{}',   -- {"webhook": url, "task": true}
    enabled         boolean NOT NULL DEFAULT true,
    origin          text NOT NULL DEFAULT 'kilnmark',
    origin_ref      text,
    created_by      text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX quality_rule_asset_idx ON quality_rule (asset_urn);

-- Shapes a steward accepted as valid for a column; each becomes a matches_pattern rule.
CREATE TABLE accepted_pattern (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    asset_urn    text NOT NULL,
    field_name   text NOT NULL,
    shape        text NOT NULL,
    regex        text NOT NULL,
    rule_id      bigint REFERENCES quality_rule (id) ON DELETE SET NULL,
    accepted_by  text NOT NULL,
    accepted_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (asset_urn, field_name, shape),
    FOREIGN KEY (asset_urn, field_name) REFERENCES field (asset_urn, name) ON DELETE CASCADE
);

-- One evaluation of an asset's rules: after a scan or on demand, run by a worker from the job queue.
CREATE TABLE quality_run (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_id     bigint NOT NULL REFERENCES data_source (id) ON DELETE CASCADE,
    asset_urn     text REFERENCES asset (urn) ON DELETE CASCADE,   -- null: every asset the scan saw
    rule_id       bigint REFERENCES quality_rule (id) ON DELETE CASCADE,
    scan_run_id   bigint REFERENCES run (id) ON DELETE SET NULL,
    trigger       text NOT NULL CHECK (trigger IN ('scan', 'manual')),
    status        text NOT NULL DEFAULT 'queued' CHECK (status IN ('queued', 'running', 'succeeded', 'failed')),
    requested_by  text,
    requested_at  timestamptz NOT NULL DEFAULT now(),
    started_at    timestamptz,
    finished_at   timestamptz,
    detail        text
);
CREATE INDEX quality_run_source_idx ON quality_run (source_id, id DESC);

CREATE TABLE quality_result (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rule_id         bigint NOT NULL REFERENCES quality_rule (id) ON DELETE CASCADE,
    quality_run_id  bigint REFERENCES quality_run (id) ON DELETE SET NULL,
    asset_urn       text NOT NULL REFERENCES asset (urn) ON DELETE CASCADE,
    status          text NOT NULL CHECK (status IN ('pass', 'warn', 'fail', 'error')),
    total           bigint,
    failing         bigint,
    method          text NOT NULL,          -- sql, stream, sample (a preview), imported
    failing_query   text,                   -- SQL that lists the failing rows, for JDBC sources
    failing_rows    jsonb,                  -- {columns, rows}: a few failing rows, if samples are kept
    message         text,                   -- for everyone: never the database's own error text
    error_detail    text,                   -- the database's error, for stewards and admins only
    estimate        boolean NOT NULL DEFAULT false,  -- checked on a sample of the table (TABLESAMPLE)
    observed_at     timestamptz NOT NULL DEFAULT now(),
    origin          text NOT NULL DEFAULT 'kilnmark',
    origin_ref      text
);
CREATE INDEX quality_result_rule_idx ON quality_result (rule_id, observed_at DESC);
CREATE INDEX quality_result_asset_idx ON quality_result (asset_urn, observed_at DESC);

-- Measures worked out from a profile version with no extra read, one per column (field '' for
-- the whole asset) and dimension, with their evidence.
CREATE TABLE quality_measure (
    version_id  bigint NOT NULL REFERENCES profile_version (id) ON DELETE CASCADE,
    field_name  text NOT NULL,
    dimension   text NOT NULL,
    score       double precision NOT NULL CHECK (score BETWEEN 0 AND 1),
    evidence    jsonb NOT NULL DEFAULT '{}',
    PRIMARY KEY (version_id, field_name, dimension)
);

-- The current scores, per column and per asset (field ''), with the reasons for each deduction.
CREATE TABLE quality_score (
    asset_urn    text NOT NULL REFERENCES asset (urn) ON DELETE CASCADE,
    field_name   text NOT NULL,
    dimension    text NOT NULL,   -- or 'overall'
    score        double precision NOT NULL CHECK (score BETWEEN 0 AND 1),
    reasons      jsonb NOT NULL DEFAULT '[]',
    computed_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (asset_urn, field_name, dimension)
);

CREATE TABLE drift_event (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    asset_urn      text NOT NULL REFERENCES asset (urn) ON DELETE CASCADE,
    field_name     text,
    kind           text NOT NULL CHECK (kind IN ('schema', 'volume', 'distribution', 'freshness')),
    change         text NOT NULL,     -- column_added, null_rate, quantile_shift, ...
    severity       text NOT NULL CHECK (severity IN ('low', 'medium', 'high')),
    message        text NOT NULL,     -- words, counts and shares only: shown to every role
    detail         text,              -- the same with the values involved: only for roles that may see data
    evidence       jsonb NOT NULL DEFAULT '{}',
    version_id     bigint NOT NULL REFERENCES profile_version (id) ON DELETE CASCADE,
    compared_with  bigint REFERENCES profile_version (id) ON DELETE SET NULL,
    against        text NOT NULL CHECK (against IN ('previous', 'baseline')),
    detected_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX drift_event_asset_idx ON drift_event (asset_urn, detected_at DESC);

-- The steward's task list: failing rules and significant drift.
CREATE TABLE quality_issue (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    asset_urn       text NOT NULL REFERENCES asset (urn) ON DELETE CASCADE,
    field_name      text,
    rule_id         bigint REFERENCES quality_rule (id) ON DELETE CASCADE,
    drift_event_id  bigint REFERENCES drift_event (id) ON DELETE SET NULL,  -- kept when old versions are pruned
    issue_key       text NOT NULL,      -- rule:<id>, or drift:<change>:<field>: one open issue per asset and key
    title           text NOT NULL,
    detail          text,
    severity        text NOT NULL CHECK (severity IN ('low', 'medium', 'high')),
    status          text NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'resolved', 'dismissed')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    closed_by       text
);
CREATE INDEX quality_issue_open_idx ON quality_issue (status, created_at DESC);
-- One open issue per asset and key: a rule that keeps failing, or drift of the same kind on the same
-- column, refreshes its open issue instead of adding more.
CREATE UNIQUE INDEX quality_issue_open_key_idx ON quality_issue (asset_urn, issue_key) WHERE status = 'open';

-- Quality evaluations go through the job queue as jobs of kind 'quality'. The constraint becomes a
-- name pattern, so other kinds of job need no further migration (and any list an earlier migration
-- set is kept as a subset).
ALTER TABLE job DROP CONSTRAINT IF EXISTS job_kind_check;
ALTER TABLE job ADD CONSTRAINT job_kind_check CHECK (kind ~ '^[a-z][a-z0-9_-]{0,31}$');

INSERT INTO install_info (key, value) VALUES ('schema_quality', '9')
ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now();

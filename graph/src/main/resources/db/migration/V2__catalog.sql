-- The catalogue, v0: data sources, scan runs, assets, fields and profiles.
-- Relational tables are the source of truth for what was found; the graph holds
-- relationships between assets in the AGE graph 'kilnmark_graph' once discovery finds them (v0.2).

SET search_path = kilnmark, public;

CREATE TABLE data_source (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        text NOT NULL UNIQUE CHECK (name ~ '^[a-z0-9][a-z0-9-]{0,62}$'),
    kind        text NOT NULL CHECK (kind IN ('postgresql')),
    jdbc_url    text NOT NULL,
    username    text,
    secret      bytea,             -- the password, encrypted; the key is never stored in the database
    schemas     text[] NOT NULL DEFAULT '{}',   -- empty: every non-system schema
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE run (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_id      bigint NOT NULL REFERENCES data_source (id) ON DELETE CASCADE,
    kind           text NOT NULL CHECK (kind IN ('scan')),
    status         text NOT NULL CHECK (status IN ('queued', 'running', 'succeeded', 'partial', 'failed')),
    requested_at   timestamptz NOT NULL DEFAULT now(),
    started_at     timestamptz,
    finished_at    timestamptz,
    assets_seen    integer NOT NULL DEFAULT 0,
    assets_failed  integer NOT NULL DEFAULT 0,
    rows_read      bigint NOT NULL DEFAULT 0,
    detail         text
);
CREATE INDEX run_source_idx ON run (source_id, id DESC);

CREATE TABLE asset (
    urn                 text PRIMARY KEY CHECK (urn LIKE 'urn:kilnmark:%'),
    source_id           bigint NOT NULL REFERENCES data_source (id) ON DELETE CASCADE,
    kind                text NOT NULL,            -- table, view, materialized view, ...
    schema_name         text NOT NULL,
    name                text NOT NULL,
    description         text,
    primary_key         text[] NOT NULL DEFAULT '{}',
    row_count           bigint,
    row_count_complete  boolean,
    first_seen_at       timestamptz NOT NULL DEFAULT now(),
    last_seen_run       bigint REFERENCES run (id) ON DELETE SET NULL,
    last_scanned_at     timestamptz,
    removed_at          timestamptz,             -- set when a scan no longer finds it
    search              tsvector GENERATED ALWAYS AS (
                            to_tsvector('simple', schema_name || ' ' || replace(name, '_', ' ') || ' '
                                                  || coalesce(description, ''))) STORED
);
CREATE INDEX asset_search_idx ON asset USING gin (search);
CREATE INDEX asset_name_trgm_idx ON asset USING gin (name gin_trgm_ops);
CREATE INDEX asset_source_idx ON asset (source_id);

CREATE TABLE field (
    asset_urn    text NOT NULL REFERENCES asset (urn) ON DELETE CASCADE,
    name         text NOT NULL,
    position     integer NOT NULL,
    source_type  text NOT NULL,
    kind         text NOT NULL,
    nullable     boolean NOT NULL,
    description  text,
    PRIMARY KEY (asset_urn, name)
);
CREATE INDEX field_name_trgm_idx ON field USING gin (name gin_trgm_ops);

CREATE TABLE profile (
    asset_urn    text NOT NULL,
    field_name   text NOT NULL,
    run_id       bigint NOT NULL REFERENCES run (id) ON DELETE CASCADE,
    stats        jsonb NOT NULL,
    observed_at  timestamptz NOT NULL,
    PRIMARY KEY (asset_urn, field_name, run_id),
    FOREIGN KEY (asset_urn, field_name) REFERENCES field (asset_urn, name) ON DELETE CASCADE
);
CREATE INDEX profile_latest_idx ON profile (asset_urn, field_name, observed_at DESC);

INSERT INTO install_info (key, value) VALUES ('schema_catalog', '2')
ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now();

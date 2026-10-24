-- AI runtime (ADR 0016): model profiles, which feature uses which model, server-wide AI settings,
-- a record of every model call, and asset embeddings for search by meaning.
-- Refers only to tables from V2 and earlier, so it applies whatever V5 and V7 do.

SET search_path = kilnmark, public;

-- A model an administrator added: where it runs, what it is for, and how hard to use it.
-- The API key is encrypted with the server's secret key (SecretBox) and never returned.
CREATE TABLE model_profile (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name             text NOT NULL UNIQUE CHECK (name ~ '^[a-z0-9][a-z0-9-]{0,62}$'),
    provider         text NOT NULL CHECK (provider IN ('openai-compatible', 'openai', 'azure-openai', 'anthropic', 'gemini')),
    base_url         text CHECK (base_url ~ '^https?://'),
    model            text NOT NULL CHECK (length(model) BETWEEN 1 AND 200),
    purpose          text NOT NULL CHECK (purpose IN ('chat', 'embedding')),
    dimensions       integer CHECK (dimensions BETWEEN 1 AND 16000),
    context_size     integer CHECK (context_size BETWEEN 256 AND 10000000),
    local            boolean NOT NULL DEFAULT false,   -- the admin asserts this server is on their network
    api_key          bytea,                            -- encrypted; null when the server needs none
    api_key_hint     text,                             -- the key's last four characters, for long keys only
    timeout_seconds  integer NOT NULL DEFAULT 60 CHECK (timeout_seconds BETWEEN 1 AND 600),
    max_concurrent   integer NOT NULL DEFAULT 2 CHECK (max_concurrent BETWEEN 1 AND 64),
    version          integer NOT NULL DEFAULT 1,       -- goes up on every change: clients are rebuilt from it
    created_by       text NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CHECK (purpose <> 'embedding' OR dimensions IS NOT NULL)
);

-- Which profile each AI feature uses. A feature without a row is off and uses its fallback.
-- A profile in use cannot be deleted until its features are moved.
CREATE TABLE ai_route (
    feature     text PRIMARY KEY CHECK (feature ~ '^[a-z][a-z_]{0,31}$'),
    profile_id  bigint NOT NULL REFERENCES model_profile (id),
    updated_by  text NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now()
);

-- Server-wide AI settings an admin can change, such as hosted_models (allowed or forbidden).
CREATE TABLE ai_setting (
    key         text PRIMARY KEY,
    value       text NOT NULL,
    updated_by  text NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now()
);

-- One row per model call, including refused ones. Prompt and answer text are not kept unless
-- kilnmark.ai.keep-usage-text is on.
CREATE TABLE ai_usage (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    at            timestamptz NOT NULL DEFAULT now(),
    feature       text NOT NULL,
    profile_id    bigint REFERENCES model_profile (id) ON DELETE SET NULL,
    profile_name  text,               -- kept when the profile is deleted
    provider      text,
    model         text,
    local         boolean,
    source_id     bigint REFERENCES data_source (id) ON DELETE SET NULL,
    source_name   text,
    tokens_in     integer,
    tokens_out    integer,
    latency_ms    integer NOT NULL DEFAULT 0,
    outcome       text NOT NULL CHECK (outcome IN ('ok', 'refused', 'failed', 'timeout', 'busy', 'cancelled')),
    detail        text,
    caller        text NOT NULL,
    prompt_text   text,
    answer_text   text
);
CREATE INDEX ai_usage_at_idx ON ai_usage (at DESC);

-- Asset embeddings for search by meaning, one per asset and embedding profile. The column has no
-- fixed size, so profiles with different dimensions share the table; each profile gets its own
-- partial HNSW index on embedding::vector(n) (or halfvec(n) above 2,000), created by the server
-- when the profile is first used. text_hash says what was embedded, so unchanged assets are skipped.
CREATE TABLE asset_embedding (
    profile_id   bigint NOT NULL REFERENCES model_profile (id) ON DELETE CASCADE,
    asset_urn    text NOT NULL REFERENCES asset (urn) ON DELETE CASCADE,
    model        text NOT NULL,
    dimensions   integer NOT NULL,
    text_hash    bytea NOT NULL,
    embedding    public.vector NOT NULL CHECK (public.vector_dims(embedding) = dimensions),
    embedded_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (profile_id, asset_urn)
);
CREATE INDEX asset_embedding_asset_idx ON asset_embedding (asset_urn);

INSERT INTO install_info (key, value) VALUES ('schema_ai', '6')
ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now();

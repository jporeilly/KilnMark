-- Kilnmark baseline: extensions and schemas only.
-- The graph model itself (DataSource, Asset, Field, Profile, Run) lands in sprint 1.

CREATE EXTENSION IF NOT EXISTS age;      -- Cypher graph (Apache AGE)
CREATE EXTENSION IF NOT EXISTS vector;   -- embeddings (pgvector)
CREATE EXTENSION IF NOT EXISTS pg_trgm;  -- fuzzy keyword search

CREATE SCHEMA IF NOT EXISTS kilnmark;

CREATE TABLE IF NOT EXISTS kilnmark.install_info (
    key        text PRIMARY KEY,
    value      text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO kilnmark.install_info (key, value) VALUES ('schema_baseline', '1')
ON CONFLICT (key) DO NOTHING;

LOAD 'age';
SET search_path = ag_catalog, "$user", public;
SELECT create_graph('kilnmark')
WHERE NOT EXISTS (SELECT 1 FROM ag_catalog.ag_graph WHERE name = 'kilnmark');

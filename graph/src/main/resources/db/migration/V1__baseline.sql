-- Kilnmark baseline: extensions and schemas only.
-- Extensions go in public by name: Flyway runs with search_path = kilnmark.
-- The catalogue tables are in V2.

CREATE EXTENSION IF NOT EXISTS age;      -- Cypher graph (Apache AGE)
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;   -- embeddings (pgvector)
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;  -- fuzzy keyword search

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
-- AGE keeps each graph in a schema of the same name, so the graph cannot be called 'kilnmark'.
SELECT create_graph('kilnmark_graph')
WHERE NOT EXISTS (SELECT 1 FROM ag_catalog.ag_graph WHERE name = 'kilnmark_graph');

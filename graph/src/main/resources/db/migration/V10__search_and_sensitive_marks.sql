-- Round 2 leftovers: bounded keyword search, and marks of sensitive columns kept apart from field rows.

-- Keyword search that stays bounded however common the words (JdbcGraphStore.search): candidates
-- are gathered through indexes, a fixed number at most, before anything is ranked.
--   * GiST trigram indexes give names in order of distance from the query (<<-> and <->), so the
--     nearest names come first and the scan stops at the limit; GIN indexes cannot order.
--   * Expression indexes find exact names (and schema.name) at once, so they always come first.
-- Field names keep their GIN trigram index (V2), used through the <% operator.
--   * The asset words index takes its entries at once instead of through a pending list
--     (fastupdate off). Every search reads the whole pending list, and until vacuum merges it the
--     planner cannot tell how many assets a word matches. Assets are written a row at a time per
--     scan, so direct entry costs little. Document chunks and fields, written in bulk, keep the
--     default: their pending lists are bounded (gin_pending_list_limit, 4 MB) and autovacuum merges
--     them, and the search no longer depends on their estimates (each branch is ordered and limited).

SET search_path = kilnmark, public;

CREATE INDEX IF NOT EXISTS asset_name_trgm_gist_idx ON asset USING gist (name gist_trgm_ops);
CREATE INDEX IF NOT EXISTS asset_qualified_trgm_gist_idx ON asset USING gist ((container || '.' || name) gist_trgm_ops);
CREATE INDEX IF NOT EXISTS asset_exact_name_idx ON asset (lower(name));
CREATE INDEX IF NOT EXISTS asset_exact_qualified_idx ON asset (lower(container || '.' || name));
ALTER INDEX asset_search_idx SET (fastupdate = off);
SELECT gin_clean_pending_list('asset_search_idx'::regclass);

-- Columns marked sensitive, kept per asset and column name apart from the field rows a scan
-- replaces. A column dropped or renamed keeps its mark, so values stored under its name (sample
-- rows, failing rows, drift detail) stay masked; a detected rename carries the mark to the new
-- name as well. Marks go with their asset.

CREATE TABLE IF NOT EXISTS sensitive_column (
    asset_urn  text NOT NULL REFERENCES asset (urn) ON DELETE CASCADE,
    name       text NOT NULL,
    marked_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (asset_urn, name)
);

INSERT INTO sensitive_column (asset_urn, name)
SELECT asset_urn, name FROM field WHERE sensitive
ON CONFLICT DO NOTHING;

ALTER TABLE field DROP COLUMN IF EXISTS sensitive;

INSERT INTO install_info (key, value) VALUES ('schema_search', '10')
ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now();

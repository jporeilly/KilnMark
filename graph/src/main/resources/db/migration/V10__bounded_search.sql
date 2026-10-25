-- Keyword search that stays bounded however common the words (JdbcGraphStore.search): candidates
-- are gathered through indexes, a fixed number at most, before anything is ranked.
--   * GiST trigram indexes give names in order of distance from the query (<<-> and <->), so the
--     nearest names come first and the scan stops at the limit; GIN indexes cannot order.
--   * Expression indexes find exact names (and schema.name) at once, so they always come first.
-- Field names keep their GIN trigram index (V2), used through the <% operator.

SET search_path = kilnmark, public;

CREATE INDEX IF NOT EXISTS asset_name_trgm_gist_idx ON asset USING gist (name gist_trgm_ops);
CREATE INDEX IF NOT EXISTS asset_qualified_trgm_gist_idx ON asset USING gist ((container || '.' || name) gist_trgm_ops);
CREATE INDEX IF NOT EXISTS asset_exact_name_idx ON asset (lower(name));
CREATE INDEX IF NOT EXISTS asset_exact_qualified_idx ON asset (lower(container || '.' || name));

INSERT INTO install_info (key, value) VALUES ('schema_search', '10')
ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now();

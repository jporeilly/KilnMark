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

INSERT INTO install_info (key, value) VALUES ('schema_search', '10')
ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now();

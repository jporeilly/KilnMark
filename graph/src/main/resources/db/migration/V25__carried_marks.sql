-- Marks follow the data. A column a pipeline writes from a column marked sensitive is marked too,
-- on the asset it writes, so what a pipeline copies stays masked wherever it lands. Those marks
-- are kept apart from the ones stewards set (column_mark, before this sensitive_column): they are
-- recorded when the run writes, before the written asset may be catalogued, and they stay when the
-- asset is removed and found again. sensitive_column is now the union of both, so everything that
-- reads marks reads both; marks are set and cleared on the tables.
-- Refers only to V10 (sensitive_column).

SET search_path = kilnmark, public;

ALTER TABLE sensitive_column RENAME TO column_mark;

CREATE TABLE carried_mark (
    asset_urn   text NOT NULL,
    name        text NOT NULL,
    from_urn    text NOT NULL,
    pipeline    text NOT NULL,
    run_id      bigint,
    carried_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (asset_urn, name)
);
CREATE INDEX carried_mark_from_idx ON carried_mark (from_urn);

CREATE VIEW sensitive_column AS
    SELECT asset_urn, name, min(marked_at) AS marked_at FROM (
        SELECT asset_urn, name, marked_at FROM column_mark
        UNION ALL
        SELECT asset_urn, name, carried_at FROM carried_mark
    ) marks
    GROUP BY asset_urn, name;

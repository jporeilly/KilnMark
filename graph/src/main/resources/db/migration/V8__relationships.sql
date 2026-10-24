-- Relationships between assets: foreign keys a source declares, and joins Kilnmark infers for a
-- person to accept or reject. Plus each column's distinct-value sketch, kept with its profile, so
-- inference can compare columns without reading data again.
-- Applies whether or not V5 to V7 have run: it refers only to tables from V2.

SET search_path = kilnmark, public;

-- One row per relationship, at field level. Multi-column keys keep their fields together, in key
-- order: from_fields[i] refers to to_fields[i]. Either asset going (a source deleted, say) takes
-- the relationship with it.
CREATE TABLE relationship (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    from_urn      text NOT NULL REFERENCES asset (urn) ON DELETE CASCADE,
    from_fields   text[] NOT NULL CHECK (cardinality(from_fields) BETWEEN 1 AND 32),
    to_urn        text NOT NULL REFERENCES asset (urn) ON DELETE CASCADE,
    to_fields     text[] NOT NULL CHECK (cardinality(to_fields) = cardinality(from_fields)),
    kind          text NOT NULL CHECK (kind IN ('declared', 'inferred')),
    status        text NOT NULL CHECK (status IN ('proposed', 'accepted', 'rejected')),
    name          text,                    -- the constraint's name in the source, for declared ones
    confidence    real CHECK (confidence BETWEEN 0 AND 1),   -- inferred ones; declared are certain
    evidence      jsonb NOT NULL DEFAULT '{}',
    decided_by    text,                    -- who accepted or rejected an inferred one
    decided_at    timestamptz,
    found_at      timestamptz NOT NULL DEFAULT now(),
    refreshed_at  timestamptz NOT NULL DEFAULT now(),   -- last scan that found it again
    CHECK (kind = 'inferred' OR status = 'accepted'),    -- a declared key needs no decision
    UNIQUE (from_urn, from_fields, to_urn, to_fields)
);
CREATE INDEX relationship_to_idx ON relationship (to_urn);
CREATE INDEX relationship_proposed_idx ON relationship (status) WHERE status = 'proposed';

-- Compact HyperLogLog sketch (lgK 14, 4-bit buckets, at most about 8 KiB) of the column's values,
-- for integer, number and text columns. Only the latest profile of a field keeps one; older rows
-- drop theirs when a new one arrives. Goes with its profile row, and so with its field and asset.
ALTER TABLE profile ADD COLUMN hll bytea;

-- Never lowers the marker, whichever of the parallel migrations (V5 to V8) runs last.
INSERT INTO install_info (key, value) VALUES ('schema_catalog', '8')
ON CONFLICT (key) DO UPDATE SET value = greatest(install_info.value::int, 8)::text, updated_at = now();

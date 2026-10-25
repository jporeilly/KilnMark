-- Columns marked sensitive, kept per asset and column name apart from the field rows a scan
-- replaces. A column dropped or renamed keeps its mark, so values stored under its name (sample
-- rows, failing rows, drift detail) stay masked; a detected rename carries the mark to the new
-- name as well. Marks go with their asset.

SET search_path = kilnmark, public;

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

INSERT INTO install_info (key, value) VALUES ('schema_sensitive', '11')
ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now();

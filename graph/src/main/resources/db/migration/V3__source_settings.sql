-- Sources of any kind: a connector kind plus its settings, instead of JDBC columns.
-- Assets sit in a container: a schema for a database, a folder or bucket for files.

SET search_path = kilnmark, public;

ALTER TABLE data_source DROP CONSTRAINT data_source_kind_check;
ALTER TABLE data_source ADD CONSTRAINT data_source_kind_check CHECK (kind ~ '^[a-z0-9][a-z0-9-]{0,31}$');
ALTER TABLE data_source ADD COLUMN settings jsonb NOT NULL DEFAULT '{}';
UPDATE data_source
SET settings = jsonb_strip_nulls(jsonb_build_object(
        'url', jdbc_url,
        'username', username,
        'schemas', nullif(array_to_string(schemas, ','), '')));
ALTER TABLE data_source DROP COLUMN jdbc_url, DROP COLUMN username, DROP COLUMN schemas;

ALTER TABLE asset RENAME COLUMN schema_name TO container;

INSERT INTO install_info (key, value) VALUES ('schema_catalog', '3')
ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now();

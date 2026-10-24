-- Source management: display names, and progress for bulk imports that test and scan.

SET search_path = kilnmark, public;

-- A source's name is its fixed key: it is part of every asset URN (urn:kilnmark:<name>:<path>), so it
-- never changes. People see, and can change, its display name.
ALTER TABLE data_source ADD COLUMN display_name text;
UPDATE data_source SET display_name = name;
ALTER TABLE data_source ALTER COLUMN display_name SET NOT NULL;
ALTER TABLE data_source ADD CONSTRAINT data_source_display_name_check
    CHECK (length(display_name) BETWEEN 1 AND 200 AND display_name !~ '[[:cntrl:]]');

-- A bulk import that also tests connections and queues scans does that work after it answers; the
-- web UI polls a batch for each row's progress. Kept for a week.
CREATE TABLE import_batch (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_by   text NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    finished_at  timestamptz,
    dry_run      boolean NOT NULL,
    test         boolean NOT NULL,
    scan         boolean NOT NULL,
    status       text NOT NULL DEFAULT 'running' CHECK (status IN ('running', 'finished'))
);

CREATE TABLE import_batch_row (
    batch_id  bigint NOT NULL REFERENCES import_batch (id) ON DELETE CASCADE,
    line      integer NOT NULL,
    name      text NOT NULL,
    test      text NOT NULL CHECK (test IN ('waiting', 'testing', 'ok', 'failed', 'skipped')),
    problem   text,               -- why the test failed, in words a person can act on; never a secret
    scan      text NOT NULL CHECK (scan IN ('waiting', 'queued', 'skipped', 'failed')),
    run_id    bigint,             -- no foreign key: deleting a source deletes its runs
    PRIMARY KEY (batch_id, line)
);

-- Never lowers the marker, whichever of the parallel migrations (V5 to V7) runs last.
INSERT INTO install_info (key, value) VALUES ('schema_catalog', '5')
ON CONFLICT (key) DO UPDATE SET value = greatest(install_info.value::int, 5)::text, updated_at = now();

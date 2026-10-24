-- Documents: what reading a document found (its profile) and its text in chunks, and assets that
-- sit inside another asset (a workbook's sheets, a mail message's attachments).
-- Everything here goes with its asset (ON DELETE CASCADE), so deleting a source, or an asset,
-- leaves no text behind. Applies whether or not V5 and V6 have run.

SET search_path = kilnmark, public;

-- The asset this one sits inside: a sheet's workbook, an attachment's message.
ALTER TABLE asset ADD COLUMN parent_urn text REFERENCES asset (urn) ON DELETE CASCADE;
CREATE INDEX asset_parent_idx ON asset (parent_urn) WHERE parent_urn IS NOT NULL;

-- One row per document asset, replaced each time the document is read.
CREATE TABLE document (
    asset_urn     text PRIMARY KEY REFERENCES asset (urn) ON DELETE CASCADE,
    run_id        bigint REFERENCES run (id) ON DELETE SET NULL,
    readable      boolean NOT NULL,
    problem       text,                          -- why it could not be read, in plain English
    format        text,                          -- file extension: pdf, docx, eml, ...
    content_type  text,
    size_bytes    bigint,
    pages         integer,                       -- pages, slides or sheets, where known
    words         bigint,
    characters    bigint,
    language      text,                          -- ISO 639-1, where clear
    title         text,
    author        text,
    created       timestamptz,                   -- from the document's own metadata
    modified      timestamptz,
    owner         text,                          -- S3 object owner, where the credentials show it
    tags          jsonb NOT NULL DEFAULT '{}',   -- S3 object tags
    mail          jsonb,                         -- from, to, cc, subject, date for mail messages
    page_starts   integer[] NOT NULL DEFAULT '{}', -- where each page, slide or sheet starts in the text
    page_labels   text[] NOT NULL DEFAULT '{}',  -- each one's own heading (a sheet's name), or null
    notes         text[] NOT NULL DEFAULT '{}',  -- what a person should know: truncation, skipped files
    truncated     boolean NOT NULL DEFAULT false,
    text_kept     boolean NOT NULL DEFAULT true, -- false when the source's keep_text is off
    chunk_count   integer NOT NULL DEFAULT 0,
    observed_at   timestamptz NOT NULL
);

-- A document's text in chunks, in order. Replaced whenever the document is read again; none for
-- sources with keep_text off. Round 3 adds an embedding column.
CREATE TABLE document_chunk (
    asset_urn     text NOT NULL REFERENCES asset (urn) ON DELETE CASCADE,
    seq           integer NOT NULL CHECK (seq >= 0),
    text          text NOT NULL,
    page          integer,                       -- page, slide or sheet it starts on, where known
    start_offset  integer NOT NULL,              -- characters into the document's extracted text
    end_offset    integer NOT NULL,
    search        tsvector GENERATED ALWAYS AS (to_tsvector('simple', text)) STORED,
    PRIMARY KEY (asset_urn, seq)
);
CREATE INDEX document_chunk_search_idx ON document_chunk USING gin (search);

INSERT INTO install_info (key, value) VALUES ('schema_documents', '7')
ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now();

-- The assistant (docs/design/assistant.md): the sections of its knowledge domains, starting with
-- the documentation that ships with the server, and people's feedback on its answers.
-- V11, not V10: another change in flight takes V10. Refers only to tables from V6 and earlier.

SET search_path = kilnmark, public;

-- One section of a knowledge domain: for docs, a part of a guide or the changelog under a ## or
-- ### heading. The server writes these at start-up from the documentation in its own jar, and
-- rewrites a section only when its hash changes. section_id is what the in-app viewer addresses
-- (quality-rules/thresholds). Embeddings are made in the background when an embedding model is
-- set; embed_key says which model made them (provider, address, model and size), so a vector from
-- another model is never compared. Sections are few (hundreds), so nearest-neighbour search scans
-- them without an HNSW index.
CREATE TABLE knowledge_section (
    domain            text NOT NULL CHECK (domain ~ '^[a-z][a-z0-9_]{0,31}$'),
    section_id        text NOT NULL CHECK (length(section_id) BETWEEN 1 AND 300),
    page              text NOT NULL,
    position          integer NOT NULL DEFAULT 0,      -- order within the page
    title_path        text NOT NULL,                    -- "Setting data quality rules › Thresholds"
    body              text NOT NULL,                    -- plain text, code kept
    hash              bytea NOT NULL,                   -- SHA-256 of title_path and body
    search            tsvector GENERATED ALWAYS AS (
                          setweight(to_tsvector('english', title_path), 'A')
                          || setweight(to_tsvector('english', body), 'B')) STORED,
    embedding         public.vector CHECK (embedding IS NULL OR public.vector_dims(embedding) = embed_dimensions),
    embed_key         bytea,
    embed_dimensions  integer,
    embedded_at       timestamptz,
    indexed_at        timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (domain, section_id)
);
CREATE INDEX knowledge_section_search_idx ON knowledge_section USING gin (search);
CREATE INDEX knowledge_section_page_idx ON knowledge_section (domain, page, position);

-- A thumbs up or down on an answer, so the documentation's gaps show up for admins. The answer's
-- own text is kept only while kilnmark.ai.keep-usage-text is on, as for usage records.
CREATE TABLE assistant_feedback (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    at           timestamptz NOT NULL DEFAULT now(),
    caller       text NOT NULL,
    question     text NOT NULL CHECK (length(question) BETWEEN 1 AND 2000),
    answer_id    text CHECK (answer_id ~ '^[0-9a-f]{8,64}$'),
    answer_text  text,
    section_ids  text[] NOT NULL DEFAULT '{}',
    page         text,                                  -- where in the app the question was asked
    helpful      boolean NOT NULL,
    comment      text CHECK (length(comment) <= 2000),
    fallback     boolean NOT NULL DEFAULT false         -- the answer was a list of sections, not a model's
);
CREATE INDEX assistant_feedback_at_idx ON assistant_feedback (at DESC);

-- Usage records (V6) name the knowledge domains an assistant call read.
ALTER TABLE ai_usage ADD COLUMN domains text[];

INSERT INTO install_info (key, value) VALUES ('schema_assistant', '11')
ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now();

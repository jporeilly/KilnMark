-- The audit log: who did what to the things that decide who sees sensitive values and what
-- reaches a source (ADR 0027, ADR 0028). One row per event, written by the server as the change
-- is made: tokens created (with or without the unmask grant) and revoked, marks cleared, sources
-- deleted, and Allow writing or Allow SQL turned on or off. Rows are only ever added: a trigger
-- refuses to change or delete them, so a mistake in the server cannot rewrite what happened.
-- Refers to no earlier table.

SET search_path = kilnmark, public;

CREATE TABLE audit_event (
    id          bigserial PRIMARY KEY,
    at          timestamptz NOT NULL DEFAULT now(),
    actor       text NOT NULL,
    token_id    bigint,
    action      text NOT NULL CHECK (action ~ '^[a-z]+(\.[a-z_]+)+$'),
    target      text NOT NULL,
    detail      jsonb NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX audit_event_at_idx ON audit_event (at DESC, id DESC);
CREATE INDEX audit_event_action_idx ON audit_event (action, at DESC);

CREATE FUNCTION audit_event_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'the audit log is append-only: % is not allowed', TG_OP;
END;
$$;

CREATE TRIGGER audit_event_append_only BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_append_only();
CREATE TRIGGER audit_event_no_truncate BEFORE TRUNCATE ON audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION audit_event_append_only();

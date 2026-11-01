-- Seeing unmasked values is a grant of its own, not a step on the role ladder (ADR 0027): only a
-- designer token may hold it, on unless created without it; admin, steward and viewer tokens never
-- do. Designer tokens made before this hold it, as new ones do by default.
-- Refers only to V4 (api_token) and V24 (its designer role).

SET search_path = kilnmark, public;

ALTER TABLE api_token ADD COLUMN unmask boolean NOT NULL DEFAULT false;
UPDATE api_token SET unmask = true WHERE role = 'designer';
ALTER TABLE api_token ADD CONSTRAINT api_token_unmask_designer_only CHECK (NOT unmask OR role = 'designer');

-- A second person approves the unmask grant (ADR 0038): with kilnmark.auth.unmask-approval on, a
-- designer token asked to see unmasked values is made without the grant, and the request waits here
-- for another admin. Who asked, and when; cleared when the grant is approved or refused.
-- Refers only to V4 (api_token) and V26 (its unmask grant).

SET search_path = kilnmark, public;

ALTER TABLE api_token ADD COLUMN unmask_requested_by text;
ALTER TABLE api_token ADD COLUMN unmask_requested_at timestamptz;
ALTER TABLE api_token ADD CONSTRAINT api_token_unmask_request_designer_only
    CHECK (unmask_requested_by IS NULL OR (role = 'designer' AND NOT unmask AND unmask_requested_at IS NOT NULL));

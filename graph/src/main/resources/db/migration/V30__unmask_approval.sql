-- Two people for the unmask grant (ADR 0038): with kilnmark.auth.unmask-approval on, a designer
-- token asked to see unmasked values is made without the grant, and the request waits here for a
-- second person. Both sign in with the identity provider; requested_key and the approver are compared
-- by the provider's fixed id for each person (issuer and subject), never by a name. Who approved it,
-- and when, stays on the token.
-- Refers only to V4 (api_token) and V26 (its unmask grant).

SET search_path = kilnmark, public;

ALTER TABLE api_token ADD COLUMN unmask_requested_by text;   -- the asker, as Kilnmark names callers
ALTER TABLE api_token ADD COLUMN unmask_requested_key text;  -- the asker's fixed id at the provider
ALTER TABLE api_token ADD COLUMN unmask_requested_at timestamptz;
ALTER TABLE api_token ADD COLUMN unmask_approved_by text;
ALTER TABLE api_token ADD COLUMN unmask_approved_at timestamptz;
ALTER TABLE api_token ADD CONSTRAINT api_token_unmask_request
    CHECK ((unmask_requested_by IS NULL) = (unmask_requested_key IS NULL)
       AND (unmask_requested_by IS NULL) = (unmask_requested_at IS NULL)
       AND (unmask_requested_by IS NULL OR (role = 'designer' AND NOT unmask)));
ALTER TABLE api_token ADD CONSTRAINT api_token_unmask_approval
    CHECK ((unmask_approved_by IS NULL) = (unmask_approved_at IS NULL) AND (unmask_approved_by IS NULL OR unmask));

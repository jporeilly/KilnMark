-- The designer role, between steward and admin: designers build pipelines, flows and sources;
-- stewards run them. Tokens may hold it, and pipeline and flow runs record it as who asked.
-- Refers only to V4 (api_token), V12 (pipeline_run) and V20 (flow_run).

SET search_path = kilnmark, public;

ALTER TABLE api_token DROP CONSTRAINT api_token_role_check;
ALTER TABLE api_token ADD CONSTRAINT api_token_role_check
    CHECK (role IN ('viewer', 'steward', 'designer', 'admin'));

ALTER TABLE pipeline_run DROP CONSTRAINT pipeline_run_requested_role_check;
ALTER TABLE pipeline_run ADD CONSTRAINT pipeline_run_requested_role_check
    CHECK (requested_role IN ('viewer', 'steward', 'designer', 'admin'));

ALTER TABLE flow_run DROP CONSTRAINT flow_run_requested_role_check;
ALTER TABLE flow_run ADD CONSTRAINT flow_run_requested_role_check
    CHECK (requested_role IN ('viewer', 'steward', 'designer', 'admin'));

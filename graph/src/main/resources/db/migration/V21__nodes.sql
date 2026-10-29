-- Steps are now called nodes, and a flow's paths connections (docs/design/pipelines.md,
-- docs/design/flows.md). Renames the tables and columns of V12, V15, V19 and V20, the run log's
-- event kinds, and the keys Kilnmark itself writes in stored JSON, so earlier runs still show.
-- Saved YAML is left as it was written (versions never change): the readers still read steps:
-- and paths:, and the pipeline canvas writes nodes: the first time it changes an old pipeline.

SET search_path = kilnmark, public;

ALTER TABLE pipeline_step_run RENAME TO pipeline_node_run;
ALTER TABLE pipeline_node_run RENAME COLUMN step_id TO node_id;

ALTER TABLE pipeline_step_quality RENAME TO pipeline_node_quality;
ALTER TABLE pipeline_node_quality RENAME COLUMN step_id TO node_id;

ALTER TABLE pipeline_lineage RENAME COLUMN step_id TO node_id;
ALTER TABLE pipeline_run_event RENAME COLUMN step_id TO node_id;
ALTER TABLE pipeline_run RENAME COLUMN series_steps TO series_nodes;

ALTER TABLE flow_step_run RENAME TO flow_node_run;
ALTER TABLE flow_node_run RENAME COLUMN step_id TO node_id;
ALTER TABLE pipeline_run RENAME COLUMN flow_step TO flow_node;
ALTER INDEX pipeline_run_flow_step_idx RENAME TO pipeline_run_flow_node_idx;

-- The constraints PostgreSQL named after the old tables (keys and foreign keys), renamed to match.
DO $$
DECLARE
    c record;
BEGIN
    FOR c IN SELECT conrelid::regclass AS tbl, conname FROM pg_constraint
             WHERE connamespace = 'kilnmark'::regnamespace AND conname LIKE '%step%' LOOP
        EXECUTE format('ALTER TABLE %s RENAME CONSTRAINT %I TO %I', c.tbl, c.conname,
                       replace(c.conname, 'step', 'node'));
    END LOOP;
END $$;

UPDATE pipeline_run_event SET kind = 'node_started' WHERE kind = 'step_started';
UPDATE pipeline_run_event SET kind = 'node_ended' WHERE kind = 'step_ended';

-- Only these keys, which Kilnmark writes, are renamed, at any depth; every value is left as it is.
-- A key that came from a pipeline (a route's port, a rule) is renamed too if it is exactly one of
-- them, such as a route output called step: unlikely, and only earlier runs' record of it changes.
CREATE FUNCTION v21_rename_keys(j jsonb) RETURNS jsonb LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    renamed CONSTANT jsonb := '{"step": "node", "steps": "nodes", "stepId": "nodeId", "stepIds": "nodeIds",
        "step_id": "node_id", "fromStep": "fromNode", "toStep": "toNode", "stepType": "nodeType",
        "stepName": "nodeName", "slowestStep": "slowestNode", "lowestOutputStep": "lowestOutputNode"}';
    out jsonb;
    k text;
    v jsonb;
BEGIN
    IF j IS NULL THEN
        RETURN NULL;
    ELSIF jsonb_typeof(j) = 'object' THEN
        out := '{}';
        FOR k, v IN SELECT * FROM jsonb_each(j) LOOP
            out := out || jsonb_build_object(coalesce(renamed ->> k, k), v21_rename_keys(v));
        END LOOP;
        RETURN out;
    ELSIF jsonb_typeof(j) = 'array' THEN
        SELECT coalesce(jsonb_agg(v21_rename_keys(e) ORDER BY i), '[]') INTO out
        FROM jsonb_array_elements(j) WITH ORDINALITY AS a(e, i);
        RETURN out;
    END IF;
    RETURN j;
END $$;

UPDATE pipeline_version SET problems = v21_rename_keys(problems) WHERE problems::text ~ '[sS]tep';
UPDATE flow_version SET problems = v21_rename_keys(problems) WHERE problems::text ~ '[sS]tep';
UPDATE pipeline_run SET result = v21_rename_keys(result) WHERE result::text ~ '[sS]tep';
UPDATE pipeline_run SET progress = v21_rename_keys(progress) WHERE progress::text ~ '[sS]tep';
UPDATE pipeline_node_quality SET
    measured_reasons = v21_rename_keys(measured_reasons),
    gate = v21_rename_keys(gate),
    predicted_detail = v21_rename_keys(predicted_detail)
WHERE coalesce(measured_reasons::text, '') || coalesce(gate::text, '') || coalesce(predicted_detail::text, '') ~ '[sS]tep';

DROP FUNCTION v21_rename_keys(jsonb);

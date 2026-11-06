-- A flow node may try its pipeline run again (ADR 0041, docs/design/flow-controls.md C4): each try is
-- a pipeline run of its own, linked to the flow run, the node, the item (for a for-each node) and its
-- try number (flow_try, 0 for the first). The unique index takes the try, so each try starts once.
-- Refers only to V20, V21 and V31 (pipeline_run).

SET search_path = kilnmark, public;

ALTER TABLE pipeline_run ADD COLUMN flow_try integer NOT NULL DEFAULT 0 CHECK (flow_try >= 0);

DROP INDEX pipeline_run_flow_node_idx;
CREATE UNIQUE INDEX pipeline_run_flow_node_idx
    ON pipeline_run (flow_run_id, flow_node, coalesce(flow_item, -1), flow_try)
    WHERE flow_run_id IS NOT NULL;

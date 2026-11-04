-- A for-each node runs one pipeline once for each item of a list (ADR 0040). Each item's run is an
-- ordinary pipeline run, linked to the flow run, the node and the item's place in the list
-- (flow_item, from 0; null for a run-pipeline node and for runs nobody's flow started). The unique
-- index moves from (flow_run_id, flow_node) to include the item, so a node's items each start once
-- and a run-pipeline node still starts its pipeline once. Refers only to V20 and V21 (pipeline_run).

SET search_path = kilnmark, public;

ALTER TABLE pipeline_run ADD COLUMN flow_item integer CHECK (flow_item >= 0);

DROP INDEX pipeline_run_flow_node_idx;
CREATE UNIQUE INDEX pipeline_run_flow_node_idx ON pipeline_run (flow_run_id, flow_node, coalesce(flow_item, -1))
    WHERE flow_run_id IS NOT NULL;

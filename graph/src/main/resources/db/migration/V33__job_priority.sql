-- Jobs have a priority (ADR 0041, docs/design/flow-controls.md C5): 1 high, 0 normal (the default,
-- for scans, quality checks and a person's Run now), -1 low. A worker claims the queued job that is
-- due with the highest priority first, then the one queued first (ADR 0040), so the index the claim
-- reads is ordered the same way. Refers only to V4 (job).
--
-- The job table keeps every job ever queued. Adding the column with a constant default rewrites
-- nothing; its check is added NOT VALID and then validated, which reads the table without blocking
-- the queue. Building the new index does block writes to the queue while it reads the table.

SET search_path = kilnmark, public;

ALTER TABLE job ADD COLUMN priority smallint NOT NULL DEFAULT 0;
ALTER TABLE job ADD CONSTRAINT job_priority_check CHECK (priority BETWEEN -1 AND 1) NOT VALID;
ALTER TABLE job VALIDATE CONSTRAINT job_priority_check;

DROP INDEX job_ready_idx;
CREATE INDEX job_ready_idx ON job (priority DESC, id) WHERE status = 'queued';

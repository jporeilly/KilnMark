-- Indexes for lookups that read whole tables. Refers only to V2 (run, asset), V4 (job), V7
-- (document), V9 (sample, quality_run, quality_result) and V12 (pipeline_run).
--
-- Foreign keys declared ON DELETE SET NULL: deleting the row they point at makes PostgreSQL find
-- every row that points at it, and without an index on the column that is a read of the whole
-- table for each row deleted. Scan runs are deleted with their source, and quality runs with
-- their source, asset or rule, so deleting a source with many runs read asset, sample, document,
-- quality_run and quality_result once per run. Partial (IS NOT NULL), since only rows that point somewhere are
-- looked for.
--
-- The job table's lookups by the run a job is for: a pipeline run's job (the workers' sweep, every
-- 30 seconds, for runs whose worker stopped, and whether a run's job is still active) and a quality
-- run's (the same sweep for evaluations), as scans' (V4) and flows' (V20) already have. Not unique: nothing has promised one job per pipeline or quality run, so a unique
-- index could refuse an existing database. The job table keeps every job ever queued, so without
-- them each lookup read all of it.
--
-- They are built without CONCURRENTLY (Flyway runs each migration in a transaction), so each build's
-- lock is held until the migration commits: writes to all six tables wait until every index is
-- built (the job table's take longest); reads go on. The migrations run at start, before the worker
-- takes jobs, so on a single Kilnmark nothing else is writing; other processes sharing the database
-- wait meanwhile.

SET search_path = kilnmark, public;

CREATE INDEX asset_last_seen_run_idx ON asset (last_seen_run) WHERE last_seen_run IS NOT NULL;
CREATE INDEX sample_run_idx ON sample (run_id) WHERE run_id IS NOT NULL;
CREATE INDEX document_run_idx ON document (run_id) WHERE run_id IS NOT NULL;
CREATE INDEX quality_run_scan_run_idx ON quality_run (scan_run_id) WHERE scan_run_id IS NOT NULL;
CREATE INDEX quality_result_quality_run_idx ON quality_result (quality_run_id) WHERE quality_run_id IS NOT NULL;

CREATE INDEX job_pipeline_run_idx ON job (((payload ->> 'pipelineRunId')::bigint)) WHERE kind = 'pipeline';
CREATE INDEX job_quality_run_idx ON job (((payload ->> 'qualityRunId')::bigint)) WHERE kind = 'quality';

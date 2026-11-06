package io.kilnmark.server.jobs;

import static io.kilnmark.server.Tokens.admin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.agroal.api.AgroalDataSource;
import io.kilnmark.graph.store.GraphStore;
import io.kilnmark.graph.store.Records;
import io.kilnmark.server.quality.QualityStore;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;

/**
 * Migration V38's indexes, and the quality runs' orphan sweep, which runs in the workers' recovery
 * and never on a request. On a server with only the api role (as {@link JobQueueTest}), so no
 * worker of its own takes the jobs or runs a recovery pass the test does not ask for.
 */
@QuarkusTest
@TestProfile(JobQueueTest.ApiOnly.class)
class QueueLookupsTest {

    private static final String PREFIX = "lookups-test";

    @Inject
    AgroalDataSource db;

    @Inject
    GraphStore store;

    @Inject
    QualityStore quality;

    @Inject
    JobWorker worker;

    @AfterEach
    void removeSources() throws Exception {
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM kilnmark.job WHERE (payload ->> 'sourceId')::bigint IN "
                + "(SELECT id FROM kilnmark.data_source WHERE name LIKE '" + PREFIX + "-%')");
            s.execute("DELETE FROM kilnmark.data_source WHERE name LIKE '" + PREFIX + "-%'");
        }
    }

    @Test
    void theNewIndexesExistOnAFreshDatabase() throws Exception {
        Set<String> found = new HashSet<>();
        try (Connection c = db.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT indexname FROM pg_indexes WHERE schemaname = 'kilnmark'")) {
            while (rs.next()) {
                found.add(rs.getString(1));
            }
        }
        for (String index : List.of("asset_last_seen_run_idx", "sample_run_idx", "document_run_idx",
            "quality_run_scan_run_idx", "quality_result_quality_run_idx", "job_pipeline_run_idx", "job_quality_run_idx")) {
            assertTrue(found.contains(index), index + " is missing");
        }
    }

    /**
     * The store's lookups of a pipeline run's job (PipelineStore.jobActive and requestCancel) and
     * the quality sweep's join (QualityStore.failOrphanedRuns) go through the new indexes. With
     * sequential scans off, as on a table too large to read whole, so the plan shows which index the
     * query can use and that it is searched by the run; without the index it falls back to reading
     * every job.
     */
    @Test
    void lookupsByRunUseTheirIndexes() throws Exception {
        Object[][] queries = {
            {"SELECT 1 FROM kilnmark.job WHERE kind = 'pipeline' AND status IN ('queued', 'running') "
                + "AND (payload ->> 'pipelineRunId')::bigint = ?", "job_pipeline_run_idx", "pipelineRunId"},
            {"UPDATE kilnmark.job SET status = 'done', finished_at = now(), last_error = 'Cancelled before it started' "
                + "WHERE kind = 'pipeline' AND status = 'queued' AND (payload ->> 'pipelineRunId')::bigint = ?",
                "job_pipeline_run_idx", "pipelineRunId"},
            {"""
                UPDATE kilnmark.quality_run r SET status = 'failed', finished_at = now(), detail = 'x'
                FROM kilnmark.job j
                WHERE j.kind = 'quality' AND j.status = 'failed' AND (j.payload ->> 'qualityRunId')::bigint = r.id
                  AND r.status IN ('queued', 'running')""", "job_quality_run_idx", "qualityRunId"},
        };
        for (Object[] q : queries) {
            StringBuilder plan = new StringBuilder();
            try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
                c.setAutoCommit(false);
                st.execute("SET LOCAL enable_seqscan = off");
                try (PreparedStatement ps = c.prepareStatement("EXPLAIN " + q[0])) {
                    if (((String) q[0]).contains("?")) {
                        ps.setLong(1, 42L);
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            plan.append(rs.getString(1)).append('\n');
                        }
                    }
                }
                c.rollback();
            }
            // The index is searched by the run, not merely read whole for its WHERE kind = ...: its
            // expression must be the query's.
            assertTrue(plan.toString().lines().anyMatch(l -> l.contains("Index Cond") && l.contains((String) q[2])),
                q[0] + "\n" + plan);
            assertTrue(plan.toString().contains((String) q[1]), q[0] + "\n" + plan);
        }
    }

    /**
     * The UI asks for a quality run every second while it waits: the request only reads. A run whose
     * job failed for good stays as it is until the workers' recovery, which marks it failed.
     */
    @Test
    void readingAQualityRunWritesNothingAndRecoveryFailsItsOrphan() throws Exception {
        long source = store.addSource(new Records.NewSource(PREFIX + "-src", "postgresql",
            Map.of("url", "jdbc:postgresql://127.0.0.1:1/none"), null)).id();
        long run = quality.queueRun(source, null, null, null, "manual", "test", 1, 3600);
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            // As a worker that died on the job's last attempt leaves it: the job failed, the run running.
            s.execute("UPDATE kilnmark.quality_run SET status = 'running', started_at = now() WHERE id = " + run);
            s.execute("UPDATE kilnmark.job SET status = 'failed', finished_at = now() WHERE kind = 'quality' "
                + "AND (payload ->> 'qualityRunId')::bigint = " + run);
        }
        for (int i = 0; i < 3; i++) {
            assertEquals("running", admin().get("/api/v1/quality/runs/" + run).then().statusCode(200).extract()
                .jsonPath().getString("status"), "a GET marked the run");
        }

        worker.recover();

        var after = admin().get("/api/v1/quality/runs/" + run).then().statusCode(200).extract().jsonPath();
        assertEquals("failed", after.getString("status"));
        assertTrue(after.getString("detail").contains("stopped each time it was tried"), after.getString("detail"));
    }
}

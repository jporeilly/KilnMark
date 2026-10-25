package io.kilnmark.server.pipelines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.Step;
import io.kilnmark.kiln.StepException;
import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.sources.HostPolicy;

/** The server's read and write steps: their checks, allow_writing, roles, allowed hosts and secrets. */
class ServerStepFactoriesTest {

    static final String SECRET = "s3cret-pass";

    private final FakeSources sources = new FakeSources()
        .add("lab-db", Map.of(), SECRET)
        .add("warehouse", Map.of("allow_writing", "true"), SECRET)
        .add("far-away", Map.of("allow_writing", "true", "host", "db.elsewhere.example"), SECRET)
        .asset("urn:kilnmark:lab-db:crm.customers", "customer_id:int64", "email:text", "joined:date");
    private final ReadStepFactory read = new ReadStepFactory(sources);
    private final WriteStepFactory write = new WriteStepFactory(sources);

    private static RunContext run(AccessRole role) {
        return new RunContext("customer-billing", 7, "jo", role);
    }

    // ---- read ----

    @Test
    void readChecksItsSourceAndRowLimit() {
        assertEquals(List.of(), read.check(Map.of("source", "lab-db", "asset", "crm.customers")));
        assertTrue(read.check(Map.of("source", "nowhere", "asset", "x")).get(0).startsWith("source: there is no saved "
            + "source called 'nowhere'"));
        assertTrue(read.check(Map.of("source", "lab-db", "asset", "x", "row_limit", -1L)).get(0)
            .startsWith("row_limit:"));
        assertTrue(read.check(Map.of("source", "lab-db", "asset", "x", "row_limit", "lots")).get(0)
            .startsWith("row_limit:"));
        assertTrue(read.check(Map.of("source", "lab-db", "asset", List.of("a", "b"))).get(0).startsWith("asset:"));
    }

    @Test
    void readColumnsComeFromTheCatalogue() throws Exception {
        Schema s = read.outputSchema("out", Map.of(), Map.of("source", "lab-db", "asset", "crm.customers"));
        assertNotNull(s);
        assertEquals(List.of("customer_id", "email", "joined"), s.getFields().stream().map(f -> f.getName()).toList());
        assertEquals("int64", ArrowKinds.kind(s.getFields().get(0)));
        assertEquals("date", ArrowKinds.kind(s.getFields().get(2)));
        assertNull(read.outputSchema("out", Map.of(), Map.of("source", "lab-db", "asset", "crm.unscanned")),
            "an asset not catalogued yet has unknown columns");
    }

    @Test
    void readStepsAreOnlyMadeForARunAndGetTheirReadersOwnSettings() throws Exception {
        StepException outside = assertThrows(StepException.class,
            () -> read.create(Map.of("source", "lab-db", "asset", "crm.customers")));
        assertTrue(outside.getMessage().contains("only when its pipeline runs"));
        RunContext ctx = run(AccessRole.STEWARD);
        Step step = ctx.run(() -> {
            try {
                return read.create(Map.of("source", "lab-db", "asset", "crm.customers", "row_limit", 10L));
            } catch (StepException e) {
                throw new IllegalStateException(e);
            }
        });
        WithSettings wrapped = assertInstanceOf(WithSettings.class, step);
        assertInstanceOf(MemoryConnector.People.class, wrapped.inner());
        assertEquals("********", ctx.scrub(SECRET), "the source's secret is noted so the run can scrub it");
    }

    @Test
    void readErrorsNeverHoldTheSecret() {
        RunContext ctx = run(AccessRole.STEWARD);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ctx.run(() -> {
            try {
                return read.create(Map.of("source", "lab-db", "asset", "crm.missing"));
            } catch (StepException x) {
                throw new IllegalStateException(x.getMessage(), x);
            }
        }));
        assertTrue(e.getMessage().contains("There is no crm.missing"), e.getMessage());
        assertFalse(e.getMessage().contains(SECRET), e.getMessage());
    }

    @Test
    void readReChecksTheAllowedHostsWhenTheRunStarts() {
        sources.hosts = new HostPolicy(List.of("*.corp.example"));
        RunContext ctx = run(AccessRole.ADMIN);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ctx.run(() -> {
            try {
                return read.create(Map.of("source", "far-away", "asset", "crm.customers"));
            } catch (StepException x) {
                throw new IllegalStateException(x.getMessage(), x);
            }
        }));
        assertTrue(e.getMessage().contains("db.elsewhere.example is not one of them"), e.getMessage());
        assertFalse(e.getMessage().contains(SECRET));
    }

    // ---- write ----

    @Test
    void writeNeedsASourceThatAllowsWriting() {
        List<String> readOnly = write.check(Map.of("source", "lab-db", "table", "billing.spend"));
        assertEquals(1, readOnly.size(), readOnly.toString());
        assertTrue(readOnly.get(0).startsWith("source: lab-db is read-only. An admin can turn on Allow writing"));
        assertEquals(List.of(), write.check(Map.of("source", "warehouse", "table", "billing.spend", "mode", "replace")));
    }

    @Test
    void writeChecksWhereAndHow() {
        assertTrue(write.check(Map.of("source", "warehouse")).get(0).startsWith("target: say where to write"));
        assertTrue(write.check(Map.of("source", "warehouse", "table", "a.b", "path", "x.csv")).get(0)
            .startsWith("path: give one of target, table or path"));
        assertTrue(write.check(Map.of("source", "warehouse", "path", "../etc/passwd")).get(0).startsWith("path: a path "
            + "inside the source"));
        assertTrue(write.check(Map.of("source", "warehouse", "path", "/etc/passwd")).get(0).startsWith("path:"));
        assertTrue(write.check(Map.of("source", "warehouse", "table", "a.b", "mode", "merge")).get(0)
            .startsWith("mode: one of create, append, replace, upsert"));
        assertTrue(write.check(Map.of("source", "warehouse", "table", "a.b", "mode", "upsert")).get(0)
            .startsWith("key: upsert needs the key columns"));
        assertEquals(List.of(), write.check(Map.of("source", "warehouse", "table", "a.b", "mode", "upsert", "key",
            List.of("customer_id"))));
        assertTrue(write.check(Map.of("source", "warehouse", "path", "out/x.csv", "format", "xlsx")).get(0)
            .startsWith("format:"));
        assertTrue(write.check(Map.of("source", "warehouse", "path", "out/x.csv", "options", "fast")).get(0)
            .startsWith("options:"));
    }

    @Test
    void writeUrnsAreScanUrns() {
        assertEquals("urn:kilnmark:warehouse:billing.spend", WriteStepFactory.urn(Map.of("source", "warehouse",
            "table", "billing.spend")));
        assertEquals("urn:kilnmark:lab-share:quarantine/orphans.csv", WriteStepFactory.urn(Map.of("source",
            "lab-share", "path", "./quarantine/orphans.csv")));
        assertEquals("create", WriteStepFactory.mode(Map.of()));
        assertEquals(List.of("a"), WriteStepFactory.key(Map.of("key", "a")));
        assertNull(WriteStepFactory.key(Map.of("key", List.of(1))));
    }

    @Test
    void viewersCannotRunWrites() {
        RunContext ctx = run(AccessRole.VIEWER);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ctx.run(() -> {
            try {
                return write.create(Map.of("source", "warehouse", "table", "billing.spend"));
            } catch (StepException x) {
                throw new IllegalStateException(x.getMessage(), x);
            }
        }));
        assertTrue(e.getMessage().contains("Only stewards and admins"), e.getMessage());
    }

    @Test
    void writeReChecksAllowWritingAndHostsWhenTheRunStarts() throws Exception {
        RunContext ctx = run(AccessRole.STEWARD);
        IllegalStateException readOnly = assertThrows(IllegalStateException.class, () -> ctx.run(() -> {
            try {
                return write.create(Map.of("source", "lab-db", "table", "billing.spend"));
            } catch (StepException x) {
                throw new IllegalStateException(x.getMessage(), x);
            }
        }));
        assertTrue(readOnly.getMessage().contains("is read-only"), readOnly.getMessage());

        sources.hosts = new HostPolicy(List.of("*.corp.example"));
        IllegalStateException host = assertThrows(IllegalStateException.class, () -> ctx.run(() -> {
            try {
                return write.create(Map.of("source", "far-away", "table", "billing.spend"));
            } catch (StepException x) {
                throw new IllegalStateException(x.getMessage(), x);
            }
        }));
        assertTrue(host.getMessage().contains("not one of them"), host.getMessage());

        Step ok = ctx.run(() -> {
            try {
                return write.create(Map.of("source", "warehouse", "table", "billing.spend", "mode", "replace"));
            } catch (StepException x) {
                throw new IllegalStateException(x);
            }
        });
        assertInstanceOf(MemoryConnector.Counter.class, ok);
    }

    @Test
    void writerErrorsNeverHoldTheSecret() {
        RunContext ctx = run(AccessRole.ADMIN);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ctx.run(() -> {
            try {
                return write.create(Map.of("source", "warehouse", "table", "billing.refuse"));
            } catch (StepException x) {
                throw new IllegalStateException(x.getMessage(), x);
            }
        }));
        assertTrue(e.getMessage().startsWith("Cannot write billing.refuse to warehouse"), e.getMessage());
        assertFalse(e.getMessage().contains(SECRET), e.getMessage());
    }
}

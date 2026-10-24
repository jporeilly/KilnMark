package io.kilnmark.spike.arrow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.arrow.memory.OutOfMemoryException;
import org.junit.jupiter.api.Test;

class SpikeTest {

    private static Spike.Result run(String... args) throws Exception {
        Spike.Result r = Spike.run(Spike.Options.parse(args));
        System.out.println(r.line());
        return r;
    }

    @Test
    void arrowAndRowObjectsProfileTheSame() throws Exception {
        Spike.Result a = run("--mode", "arrow", "--rows", "200000");
        Spike.Result r = run("--mode", "rows", "--rows", "200000");
        for (String c : new String[] {"txn_id", "account_id", "amount", "merchant", "reference", "txn_at"}) {
            ColumnProfile x = a.profile().column(c);
            ColumnProfile y = r.profile().column(c);
            assertEquals(y.count, x.count, c + " count");
            assertEquals(y.nulls, x.nulls, c + " nulls");
            assertEquals(y.distinct.getEstimate(), x.distinct.getEstimate(), 1e-9, c + " distinct");
        }
        assertEquals(0, a.arrowLeakedBytes());
    }

    @Test
    void distinctCountsAreAccurate() throws Exception {
        Spike.Result a = run("--mode", "arrow", "--rows", "500000");
        assertWithin(SyntheticSource.ACCOUNTS, a.profile().column("account_id").distinct.getEstimate(), 0.05);
        assertWithin(SyntheticSource.MERCHANTS.length, a.profile().column("merchant").distinct.getEstimate(), 0.01);
        assertWithin(SyntheticSource.expectedDistinctReferences(500_000),
            a.profile().column("reference").distinct.getEstimate(), 0.05);
        assertWithin(500_000, a.profile().column("txn_id").distinct.getEstimate(), 0.05);
    }

    @Test
    void partitionsProfiledApartMergeToTheSameAnswer() throws Exception {
        Spike.Result one = run("--mode", "arrow", "--rows", "500000", "--consumers", "1");
        Spike.Result four = run("--mode", "arrow", "--rows", "500000", "--consumers", "4");
        assertEquals(one.rows(), four.rows());
        for (String c : new String[] {"account_id", "reference", "txn_id"}) {
            assertWithin(one.profile().column(c).distinct.getEstimate(),
                four.profile().column(c).distinct.getEstimate(), 0.02);
        }
        assertWithin(one.profile().column("amount").quantiles.getQuantile(0.5),
            four.profile().column("amount").quantiles.getQuantile(0.5), 0.03);
        assertEquals(0, four.arrowLeakedBytes());
    }

    @Test
    void backPressureKeepsMemoryBoundedBelowTheDataSize() throws Exception {
        // About 80 MB of batches through a 24 MB limit: it only fits because the source waits.
        Spike.Result r = run("--mode", "arrow", "--rows", "819200", "--queue", "2", "--slow-ms", "2",
            "--limit-mb", "24");
        assertTrue(r.deepestQueue() <= 2, "queue depth " + r.deepestQueue());
        assertTrue(r.arrowPeakBytes() < 24L * 1024 * 1024, "peak " + r.arrowPeakBytes());
        assertEquals(0, r.arrowLeakedBytes());
    }

    @Test
    void runningOutOfArrowMemoryFailsLoudlyInsteadOfHanging() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> run("--mode", "arrow", "--rows", "819200", "--queue", "64", "--slow-ms", "5", "--limit-mb", "8"));
        Throwable cause = e;
        while (cause.getCause() != null && !(cause instanceof OutOfMemoryException)) {
            cause = cause.getCause();
        }
        assertInstanceOf(OutOfMemoryException.class, cause);
    }

    private static void assertWithin(double expected, double actual, double fraction) {
        assertTrue(Math.abs(actual - expected) <= Math.abs(expected) * fraction,
            "expected " + expected + " within " + fraction * 100 + "% but was " + actual);
    }
}

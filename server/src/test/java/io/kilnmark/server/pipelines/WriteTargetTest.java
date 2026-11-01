package io.kilnmark.server.pipelines;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** A write's path is written one way, as the source lists it, so what is recorded about it is found again. */
class WriteTargetTest {

    @Test
    void onePathOneSpelling() {
        for (String ok : new String[] {"exports/spend.csv", "billing.customer_spend", "a/b/c.parquet", "x.csv"}) {
            assertNull(WriteNodeFactory.targetProblem(ok), ok);
        }
        for (String bad : new String[] {"a//b.csv", "a/./b.csv", "./a/./b", "a\b.csv", "exports/", "../x.csv",
            "/abs.csv", "C:x.csv"}) {
            assertNotNull(WriteNodeFactory.targetProblem(bad), bad);
        }
    }
}

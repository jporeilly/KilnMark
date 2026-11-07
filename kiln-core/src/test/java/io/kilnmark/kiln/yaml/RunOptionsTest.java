package io.kilnmark.kiln.yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.Test;

/** The {@code run:} block of a pipeline and a flow (ADR 0053). */
class RunOptionsTest {

    private static final String PIPELINE = """
        kilnmark: pipeline/v1
        name: orders
        RUN
        nodes:
          - id: in
            type: read
        """;

    private static final String FLOW = """
        kilnmark: flow/v1
        name: nightly
        RUN
        nodes:
          - id: load
            type: run-pipeline
            pipeline: orders
        """;

    private static PipelineDefinition.Parsed pipeline(String run) {
        return PipelineDefinition.parse(PIPELINE.replace("RUN\n", run));
    }

    private static List<String> problems(String run) {
        return pipeline(run).problems().stream().map(Problem::message).toList();
    }

    @Test
    void readsEverySettingOnAPipelineAndAFlow() {
        String run = "run:\n  retries: 2\n  retry_delay: 5m\n  retry_on: lost\n  timeout: 2h\n  warn_after: 1h\n";
        PipelineDefinition.Parsed p = pipeline(run);
        assertTrue(p.problems().isEmpty(), p.problems().toString());
        RunOptions o = p.definition().run();
        assertEquals(2, o.retries());
        assertEquals(Duration.ofMinutes(5), o.retryDelay());
        assertEquals(RunOptions.RetryOn.LOST, o.retryOn());
        assertEquals(Duration.ofHours(2), o.timeout());
        assertEquals(Duration.ofHours(1), o.warnAfter());
        assertEquals(3, o.at().line());

        FlowDefinition.Parsed f = FlowDefinition.parse(FLOW.replace("RUN\n", run));
        assertTrue(f.problems().isEmpty(), f.problems().toString());
        assertEquals(2, f.definition().run().retries());
        assertEquals(RunOptions.RetryOn.LOST, f.definition().run().retryOn());
    }

    @Test
    void theDefaultsAreOneTryNoLimitAndNoWarning() {
        RunOptions none = pipeline("").definition().run();
        assertSame(RunOptions.NONE, none);
        assertEquals(0, none.retries());
        assertEquals(Duration.ofMinutes(1), none.retryDelay());
        assertEquals(RunOptions.RetryOn.ANY, none.retryOn());
        assertNull(none.timeout());
        assertNull(none.warnAfter());
        assertEquals(1, none.tries(5));
        RunOptions some = pipeline("run:\n  retries: 3\n").definition().run();
        assertEquals(Duration.ofMinutes(1), some.retryDelay(), "1m unless written");
        assertEquals(RunOptions.RetryOn.ANY, some.retryOn(), "any unless written");
    }

    @Test
    void refusesWhatIsOutOfRange() {
        assertTrue(problems("run:\n  retries: 6\n").getFirst().contains("from 0 to 5"));
        assertTrue(problems("run:\n  retries: -1\n").getFirst().contains("from 0 to 5"));
        assertTrue(problems("run:\n  retries: two\n").getFirst().contains("from 0 to 5"));
        assertTrue(problems("run:\n  retries: 1\n  retry_delay: 9s\n").getFirst().contains("10s to 1h"));
        assertTrue(problems("run:\n  retries: 1\n  retry_delay: 61m\n").getFirst().contains("10s to 1h"));
        assertTrue(problems("run:\n  retries: 1\n  retry_on: sometimes\n").getFirst().contains("any"));
        assertTrue(problems("run:\n  timeout: 89s\n").getFirst().contains("90s to 7d"));
        assertTrue(problems("run:\n  timeout: 8d\n").getFirst().contains("90s to 7d"));
        assertTrue(problems("run:\n  warn_after: 30s\n").getFirst().contains("1m to 7d"));
        assertTrue(problems("run:\n  timeout: 1h\n  warn_after: 1h\n").getFirst().contains("less than run.timeout"));
        assertTrue(problems("run:\n  timeout: 1h\n  warn_after: 2h\n").getFirst().contains("less than run.timeout"));
        assertTrue(problems("run:\n  retry_delay: 1m\n").getFirst().contains("set run.retries too"));
        assertTrue(problems("run:\n  retry_on: lost\n").getFirst().contains("set run.retries too"));
        assertTrue(problems("run:\n  retires: 2\n").getFirst().contains("has no setting 'retires'"));
        assertTrue(problems("run: 3\n").getFirst().contains("must be a mapping"));
        assertTrue(problems("run:\n  timeout: 90s\n  warn_after: 1m\n").isEmpty(), "the least of each");
        assertTrue(problems("run:\n  retries: 5\n  retry_delay: 1h\n  timeout: 7d\n  warn_after: 6d\n").isEmpty(),
            "the most of each");
    }

    @Test
    void eachWaitDoublesAndIsCappedAtAnHour() {
        Duration d = Duration.ofMinutes(10);
        assertEquals(Duration.ofMinutes(10), RunOptions.wait(d, 1));
        assertEquals(Duration.ofMinutes(20), RunOptions.wait(d, 2));
        assertEquals(Duration.ofMinutes(40), RunOptions.wait(d, 3));
        assertEquals(Duration.ofHours(1), RunOptions.wait(d, 4), "80m is capped at an hour");
        assertEquals(Duration.ofHours(1), RunOptions.wait(d, 50), "however often it doubles");
        // Five tries again from the longest delay wait five hours in all, never 31.
        Duration total = Duration.ZERO;
        for (int n = 1; n <= RunOptions.MAX_RETRIES; n++) {
            total = total.plus(RunOptions.wait(RunOptions.MAX_RETRY_DELAY, n));
        }
        assertEquals(Duration.ofHours(5), total);
    }

    @Test
    void theRandomPartAddsAtMostATenth() {
        Duration d = Duration.ofMinutes(10);
        assertEquals(Duration.ofMinutes(20), RunOptions.waitWithJitter(d, 2, fixed(0.0)));
        Duration most = RunOptions.waitWithJitter(d, 2, fixed(0.999_999_999));
        assertTrue(most.compareTo(Duration.ofMinutes(22)) <= 0 && most.compareTo(Duration.ofSeconds(1319)) >= 0,
            most.toString());
        RandomGenerator any = RandomGenerator.of("L64X128MixRandom");
        for (int i = 0; i < 1_000; i++) {
            Duration w = RunOptions.waitWithJitter(Duration.ofHours(1), 3, any);
            assertTrue(w.compareTo(Duration.ofHours(1)) >= 0 && w.compareTo(Duration.ofMinutes(66)) <= 0, w.toString());
        }
    }

    @Test
    void nodeRetriesAreCappedToo() {
        FlowDefinition.Retry r = new FlowDefinition.Retry(5, Duration.ofHours(1));
        assertEquals(Duration.ofHours(1), r.before(5), "16h before ADR 0053; an hour now");
    }

    @Test
    void theServerCapLimitsTheTries() {
        RunOptions o = pipeline("run:\n  retries: 4\n").definition().run();
        assertEquals(5, o.tries(5));
        assertEquals(3, o.tries(2));
        assertEquals(1, o.tries(0), "0 turns run retries off");
    }

    private static RandomGenerator fixed(double v) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                return 0;
            }

            @Override
            public double nextDouble() {
                return v;
            }
        };
    }
}

package io.kilnmark.kiln.yaml;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/**
 * How a pipeline's or a flow's runs are tried (ADR 0053): its top-level {@code run:} block.
 *
 * <pre>{@code
 * run:
 *   retries: 2         # 0 to 5; 0 by default
 *   retry_delay: 5m    # 10s to 1h; 1m by default; doubled before each later try, each wait at most 1h
 *   retry_on: any      # any (the default) or lost
 *   timeout: 2h        # each try; 90s to 7d; none by default
 *   warn_after: 1h     # 1m to 7d, less than timeout; none by default
 * }</pre>
 *
 * Whether trying a run again could write twice is for the server to check, against what it runs.
 *
 * @param retries    how many more tries after a failed one
 * @param retryDelay the wait before the second try, doubled before each later one
 * @param retryOn    which failures are tried again
 * @param timeout    how long each try may take, or null for no limit
 * @param warnAfter  how long a run may go before its owners are told it is taking long, or null
 * @param at         where {@code run:} is written ({@link Position#START} when it is not), for problems
 *                   the server finds about it
 */
public record RunOptions(int retries, Duration retryDelay, RetryOn retryOn, Duration timeout, Duration warnAfter,
                         Position at) {

    /** Which failures are tried again. */
    public enum RetryOn {
        /** Any failure a second try could change (ADR 0053, decision 4). */
        ANY,
        /** Only a try lost with its worker or its connection to Kilnmark's database. */
        LOST;

        /** As written: {@code any} or {@code lost}. */
        public String written() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The most tries again a run may have. */
    public static final int MAX_RETRIES = 5;
    /** The wait before the second try when not written, and the least and most it may be. */
    public static final Duration DEFAULT_RETRY_DELAY = Duration.ofMinutes(1);
    public static final Duration MIN_RETRY_DELAY = Duration.ofSeconds(10);
    public static final Duration MAX_RETRY_DELAY = Duration.ofHours(1);
    /** The longest any one wait may be, however often the delay has doubled. */
    public static final Duration MAX_WAIT = Duration.ofHours(1);
    /** The least and most a try's timeout may be. */
    public static final Duration MIN_TIMEOUT = Duration.ofSeconds(90);
    public static final Duration MAX_TIMEOUT = Duration.ofDays(7);
    /** The least and most {@code warn_after} may be. */
    public static final Duration MIN_WARN_AFTER = Duration.ofMinutes(1);
    public static final Duration MAX_WARN_AFTER = Duration.ofDays(7);
    /** The most random spread added to a wait, as a share of it, so runs failing together come back apart. */
    public static final double JITTER = 0.10;

    /** The block's keys, in the order the guide gives them. */
    public static final List<String> KEYS = List.of("retries", "retry_delay", "retry_on", "timeout", "warn_after");
    private static final Set<String> KEY_SET = Set.copyOf(KEYS);
    private static final Pattern TIME = Pattern.compile("(\\d{1,6})\\s*(s|m|h|d)");

    /** No {@code run:} block: one try, no limit, no warning. */
    public static final RunOptions NONE = new RunOptions(0, DEFAULT_RETRY_DELAY, RetryOn.ANY, null, null,
        Position.START);

    public RunOptions {
        retryDelay = retryDelay == null ? DEFAULT_RETRY_DELAY : retryDelay;
        retryOn = retryOn == null ? RetryOn.ANY : retryOn;
        at = at == null ? Position.START : at;
    }

    /**
     * The wait before try {@code n + 1}, after try {@code n} (from 1) failed: the delay doubled
     * {@code n - 1} times, never more than {@link #MAX_WAIT}. With no random part.
     */
    public static Duration wait(Duration delay, int n) {
        Duration d = delay.multipliedBy(1L << Math.max(0, Math.min(n - 1, 20)));
        return d.compareTo(MAX_WAIT) > 0 ? MAX_WAIT : d;
    }

    /** {@link #wait(Duration, int)} with up to {@link #JITTER} of it added at random, by {@code random}. */
    public static Duration waitWithJitter(Duration delay, int n, RandomGenerator random) {
        Duration w = wait(delay, n);
        long extra = (long) (w.toMillis() * JITTER * random.nextDouble());
        return w.plusMillis(Math.max(0, extra));
    }

    /** The wait before try {@code n + 1} of this run, without the random part. */
    public Duration waitAfter(int n) {
        return wait(retryDelay, n);
    }

    /** The tries a run may have, given the server's own cap {@code serverMax} (0 turns retries off). */
    public int tries(int serverMax) {
        return 1 + Math.max(0, Math.min(retries, serverMax));
    }

    /**
     * Reads a {@code run:} block; {@link #NONE} when it is not written, or the settings it could read
     * (with a problem for each it could not).
     */
    static RunOptions read(YamlReader.Entry e, List<Problem> problems) {
        if (e == null) {
            return NONE;
        }
        if (!(e.value() instanceof YamlReader.Mapping m)) {
            problems.add(Problem.at(e.value().position(), null, "run must be a mapping, such as run: { retries: 2, "
                + "retry_delay: 5m }"));
            return new RunOptions(0, DEFAULT_RETRY_DELAY, RetryOn.ANY, null, null, e.keyPosition());
        }
        for (YamlReader.Entry k : m.entries().values()) {
            if (!KEY_SET.contains(k.key())) {
                problems.add(Problem.at(k.keyPosition(), null, "run has no setting '" + Suggest.clip(k.key()) + "'"
                    + Suggest.hint(k.key(), KEY_SET) + ": it takes retries, retry_delay, retry_on, timeout and "
                    + "warn_after"));
            }
        }
        YamlReader.Entry r = m.entries().get("retries");
        int retries = 0;
        if (r != null) {
            if (r.value().value() instanceof Integer n && n >= 0 && n <= MAX_RETRIES) {
                retries = n;
            } else {
                problems.add(Problem.at(r.value().position(), null, "run.retries is how many more times a failed run "
                    + "is tried: a whole number from 0 to " + MAX_RETRIES));
            }
        }
        YamlReader.Entry d = m.entries().get("retry_delay");
        YamlReader.Entry on = m.entries().get("retry_on");
        if (r == null) {
            for (YamlReader.Entry without : new YamlReader.Entry[] {d, on}) {
                if (without != null) {
                    problems.add(Problem.at(without.keyPosition(), null, "run." + without.key() + " says how a failed "
                        + "run is tried again: set run.retries too"));
                }
            }
        }
        Duration delay = time(d, MIN_RETRY_DELAY, MAX_RETRY_DELAY, "run.retry_delay is the wait before the second "
            + "try, doubled before each later one: 10s to 1h, such as 5m", problems);
        RetryOn retryOn = RetryOn.ANY;
        if (on != null) {
            String word = on.value() instanceof YamlReader.Scalar sc && sc.value() instanceof String t
                ? t.strip().toLowerCase(Locale.ROOT) : "";
            switch (word) {
                case "any" -> retryOn = RetryOn.ANY;
                case "lost" -> retryOn = RetryOn.LOST;
                default -> problems.add(Problem.at(on.value().position(), null, "run.retry_on is any (any failure a "
                    + "second try could change) or lost (only a run lost with its worker)"));
            }
        }
        Duration timeout = time(m.entries().get("timeout"), MIN_TIMEOUT, MAX_TIMEOUT, "run.timeout is how long each "
            + "try may take before it is stopped: 90s to 7d, such as 2h", problems);
        YamlReader.Entry w = m.entries().get("warn_after");
        Duration warnAfter = time(w, MIN_WARN_AFTER, MAX_WARN_AFTER, "run.warn_after is how long a run may go "
            + "before its owners are told it is taking long: 1m to 7d, such as 1h", problems);
        if (warnAfter != null && timeout != null && warnAfter.compareTo(timeout) >= 0) {
            problems.add(Problem.at(w.value().position(), null, "run.warn_after must be less than run.timeout, or "
                + "the try is stopped before anyone is told it is taking long"));
            warnAfter = null;
        }
        return new RunOptions(retries, delay == null ? DEFAULT_RETRY_DELAY : delay, retryOn, timeout, warnAfter,
            e.keyPosition());
    }

    /** A time such as 90s, 30m, 2h or 1d between {@code min} and {@code max}; null when absent or wrong. */
    private static Duration time(YamlReader.Entry e, Duration min, Duration max, String what, List<Problem> problems) {
        if (e == null) {
            return null;
        }
        Duration d = parse(e.value() instanceof YamlReader.Scalar sc && sc.value() != null ? sc.written() : null);
        if (d == null || d.compareTo(min) < 0 || d.compareTo(max) > 0) {
            problems.add(Problem.at(e.value().position(), null, what));
            return null;
        }
        return d;
    }

    /** A time written 90s, 30m, 2h or 1d; null when it is not one. */
    static Duration parse(String written) {
        if (written == null) {
            return null;
        }
        var matcher = TIME.matcher(written.strip().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            return null;
        }
        long n = Long.parseLong(matcher.group(1));
        return switch (matcher.group(2)) {
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            case "h" -> Duration.ofHours(n);
            default -> Duration.ofDays(n);
        };
    }
}

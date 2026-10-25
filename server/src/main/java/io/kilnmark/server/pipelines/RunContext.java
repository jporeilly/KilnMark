package io.kilnmark.server.pipelines;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import io.kilnmark.server.auth.AccessRole;

/**
 * Whose run is being set up, for the server's {@code read} and {@code write} step factories while
 * the compiler creates the steps of one run: who asked and with which role (write steps check it
 * again), and the secrets the steps were given, so nothing the run reports can hold one. Bound
 * with {@link #run} for the length of the compile only.
 */
public final class RunContext {

    private static final ScopedValue<RunContext> CURRENT = ScopedValue.newInstance();

    private final String pipeline;
    private final long runId;
    private final String requestedBy;
    private final AccessRole role;
    private final Set<String> secrets = Collections.synchronizedSet(new LinkedHashSet<>());

    public RunContext(String pipeline, long runId, String requestedBy, AccessRole role) {
        this.pipeline = pipeline;
        this.runId = runId;
        this.requestedBy = requestedBy;
        this.role = role;
    }

    /** The context of the run being set up on this thread, if any. */
    public static Optional<RunContext> current() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }

    /** Runs {@code work} with this context bound. */
    public <T> T run(java.util.function.Supplier<T> work) {
        return ScopedValue.where(CURRENT, this).call(work::get);
    }

    public String pipeline() {
        return pipeline;
    }

    public long runId() {
        return runId;
    }

    public String requestedBy() {
        return requestedBy;
    }

    public AccessRole role() {
        return role;
    }

    /** Remembers a secret a step was given, so no message the run records can hold it. */
    void secret(String secret) {
        if (secret != null && secret.length() >= 3) {
            secrets.add(secret);
        }
    }

    /** {@code message} with every secret this run resolved blanked out. */
    public String scrub(String message) {
        if (message == null) {
            return null;
        }
        String out = message;
        synchronized (secrets) {
            for (String s : secrets) {
                out = out.replace(s, "********");
            }
        }
        return out;
    }
}

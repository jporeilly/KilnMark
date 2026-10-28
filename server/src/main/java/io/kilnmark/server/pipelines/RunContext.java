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
    private final Set<String> reads = Collections.synchronizedSet(new LinkedHashSet<>());
    private final java.util.Map<String, String> writes = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, String> readUrns = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, java.util.List<String>> writeColumns =
        new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, ReadRef> gateReads = new java.util.concurrent.ConcurrentHashMap<>();

    /** A read step's source and asset, as its settings name them. */
    public record ReadRef(String source, String asset) {
    }

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

    /** Notes an asset a read step reads, under the URN the catalogue records it by. */
    void read(String source, String asset, String urn) {
        if (urn != null) {
            reads.add(urn);
            if (source != null && asset != null) {
                readUrns.put(source + "\u0000" + asset, urn);
            }
        }
    }

    /**
     * The URN the read of {@code asset} (as the YAML names it) in {@code source} is catalogued
     * under, as its connector resolved it (a file's {@code #} and {@code %} escaped, say), or
     * {@code fallback}.
     */
    public String readUrn(String source, String asset, String fallback) {
        return source == null || asset == null ? fallback : readUrns.getOrDefault(source + "\u0000" + asset, fallback);
    }

    /** Notes the columns arriving at the write of {@code target} in {@code source}, as the pipeline check worked them out. */
    void writeColumns(String source, String target, java.util.List<String> columns) {
        if (source != null && target != null && columns != null) {
            writeColumns.put(source + "\u0000" + target, java.util.List.copyOf(columns));
        }
    }

    /** The columns arriving at a write, or empty when they were not known before the run. */
    public java.util.List<String> writeColumns(String source, String target) {
        return source == null || target == null ? java.util.List.of()
            : writeColumns.getOrDefault(source + "\u0000" + target, java.util.List.of());
    }

    /** Notes the read step whose asset's rules gate {@code gate} checks. */
    void gateRead(String gate, ReadRef read) {
        if (gate != null && read != null && read.source() != null && read.asset() != null) {
            gateReads.put(gate, read);
        }
    }

    /** The read whose asset's rules gate {@code gate} checks, or null for none. */
    public ReadRef gateRead(String gate) {
        return gate == null ? null : gateReads.get(gate);
    }

    /** The assets the run's read steps read, as catalogued. */
    public Set<String> reads() {
        synchronized (reads) {
            return Set.copyOf(reads);
        }
    }

    /** Notes the URN a write step's asset is recorded under, as its connector worked it out. */
    void wrote(String source, String target, String urn) {
        if (source != null && target != null && urn != null) {
            writes.put(source + "\u0000" + target, urn);
        }
    }

    /** The URN the write of {@code target} in {@code source} is recorded under, or {@code fallback}. */
    public String writtenUrn(String source, String target, String fallback) {
        return source == null || target == null ? fallback : writes.getOrDefault(source + "\u0000" + target, fallback);
    }

    /**
     * {@code message} with every secret this run resolved blanked out, and any NUL character dropped
     * (the database cannot store one in text).
     */
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
        return out.indexOf('\u0000') < 0 ? out : out.replace("\u0000", "");
    }
}

package io.kilnmark.server.ai;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import org.jboss.logging.Logger;

import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import io.kilnmark.graph.store.Records;
import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.ai.AiException.Kind;
import io.kilnmark.server.ai.AiFeature.Purpose;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Every model call goes through here, in this order: check the model's address
 * ({@link ModelHosts}), check the egress rule ({@link EgressPolicy}), wait for a free slot (the
 * profile's {@code max_concurrent}, per process), call within the {@link Budget}, retrying with
 * backoff, stop if cancelled, and write a usage record whatever happened.
 *
 * <p>Clients are built from the stored profile and rebuilt when its version changes, so adding or
 * changing a model needs no restart, in this process or any other.
 *
 * <p>Messages in {@link AiException} can hold the model's address and the server's reply: they
 * are for admins (usage records, the profile test). Anything shown to viewers uses its own words.
 */
@ApplicationScoped
public class AiGateway {

    private static final Logger LOG = Logger.getLogger(AiGateway.class);
    private static final long CANCEL_POLL_MS = 200;
    /** After a timeout or an unreachable server, interactive calls skip the model this long. */
    static final long DOWN_FOR_MS = 30_000;

    private final ModelProfiles profiles;
    private final EgressPolicy egress;
    private final ModelHosts hosts;
    private final UsageLog usage;
    private final KilnmarkConfig config;
    private final Map<Long, Built> clients = new ConcurrentHashMap<>();
    /** Profile id to the time until which interactive calls skip it. */
    private final Map<Long, Long> downUntil = new ConcurrentHashMap<>();

    /** A client for one version of a profile, with that profile's concurrency limit. */
    private record Built(int version, ModelClient client, Semaphore slots, int maxConcurrent) {
    }

    /**
     * How long a call may take. Interactive calls (a search, a question) wait briefly for a slot,
     * do not retry a timeout, give up after about ten seconds, and skip a model that failed in the
     * last 30 seconds. Background calls (embedding at scan, the profile test) use the profile's
     * timeout and the configured retries.
     *
     * @param slotWaitMs most time waiting for a free slot; 0 means the profile's timeout
     * @param totalMs    most time for the whole call, retries included; 0 means no overall limit
     */
    public record Budget(long slotWaitMs, boolean retryTimeouts, long totalMs, boolean skipIfDown) {
        public static final Budget INTERACTIVE = new Budget(2_000, false, 10_000, true);
        public static final Budget BACKGROUND = new Budget(0, true, 0, false);
    }

    public AiGateway(ModelProfiles profiles, EgressPolicy egress, ModelHosts hosts, UsageLog usage,
                     KilnmarkConfig config) {
        this.profiles = profiles;
        this.egress = egress;
        this.hosts = hosts;
        this.usage = usage;
        this.config = config;
    }

    /** An answer and the profile that gave it. */
    public record Result<T>(T answer, ModelProfile profile, long latencyMs) {
    }

    /** The profile a feature uses, if any. */
    public Optional<ModelProfile> profileFor(AiFeature feature) {
        return profiles.route(feature);
    }

    /**
     * Chat for {@code feature}, about {@code source} (null when the prompt carries no source's
     * metadata), within the background budget.
     *
     * @throws AiException when the feature is off, the call is refused, fails, times out or is cancelled
     */
    public Result<ModelClient.ChatAnswer> chat(AiFeature feature, Records.Source source, ModelClient.ChatCall call,
                                               String caller, BooleanSupplier cancelled) {
        return chatWith(routed(feature), feature.id(), source, call, caller, cancelled, Budget.BACKGROUND);
    }

    /** Embeddings for {@code feature} (see {@link #chat}). */
    public Result<ModelClient.EmbedAnswer> embed(AiFeature feature, Records.Source source, List<String> texts,
                                                 String caller, BooleanSupplier cancelled) {
        return embedWith(routed(feature), feature.id(), source, texts, caller, cancelled, Budget.BACKGROUND);
    }

    private ModelProfile routed(AiFeature feature) {
        return profiles.route(feature).orElseThrow(() -> new AiException(Kind.UNAVAILABLE, "No model is set for "
            + feature.label().toLowerCase(Locale.ROOT) + ". An admin can choose one under AI models."));
    }

    /** Chat with a given profile, under the same rules. */
    public Result<ModelClient.ChatAnswer> chatWith(ModelProfile p, String feature, Records.Source source,
                                                   ModelClient.ChatCall call, String caller, BooleanSupplier cancelled,
                                                   Budget budget) {
        return chatWith(p, feature, source, call, caller, cancelled, budget, null);
    }

    /**
     * Chat with a given profile, under the same rules, recording the knowledge domains the prompt
     * drew on (the assistant) in the usage record.
     */
    public Result<ModelClient.ChatAnswer> chatWith(ModelProfile p, String feature, Records.Source source,
                                                   ModelClient.ChatCall call, String caller, BooleanSupplier cancelled,
                                                   Budget budget, List<String> domains) {
        if (p.purpose() != Purpose.CHAT) {
            throw new AiException(Kind.FAILED, "Profile " + p.name() + " is an embedding model; this needs a chat model.");
        }
        String prompt = call.system() + "\n\n" + call.user();
        return run(p, feature, source, caller, cancelled, budget, prompt, c -> c.chat(call),
            a -> new long[] {nz(a.tokensIn()), nz(a.tokensOut())}, ModelClient.ChatAnswer::text, a -> null, domains);
    }

    /**
     * Embeddings with a given profile. An answer with the wrong number of vectors, or vectors of
     * another size than the profile's dimensions, is a failure (and recorded as one).
     */
    public Result<ModelClient.EmbedAnswer> embedWith(ModelProfile p, String feature, Records.Source source,
                                                     List<String> texts, String caller, BooleanSupplier cancelled,
                                                     Budget budget) {
        if (p.purpose() != Purpose.EMBEDDING) {
            throw new AiException(Kind.FAILED, "Profile " + p.name() + " is a chat model; this needs an embedding model.");
        }
        return run(p, feature, source, caller, cancelled, budget, String.join("\n---\n", texts),
            c -> c.embed(texts), a -> new long[] {nz(a.tokensIn()), -1}, a -> null,
            a -> wrongShape(p, texts.size(), a), null);
    }

    /** Why an embedding answer does not fit the profile, or null. */
    static String wrongShape(ModelProfile p, int expected, ModelClient.EmbedAnswer a) {
        if (a.vectors().size() != expected) {
            return "Model " + p.model() + " returned " + a.vectors().size() + " embeddings for " + expected + " texts.";
        }
        for (float[] v : a.vectors()) {
            if (v.length != p.dimensions()) {
                return "Model " + p.model() + " returns " + v.length + " numbers per text, but profile " + p.name()
                    + " says " + p.dimensions() + ". Set the profile's dimensions to " + v.length + ".";
            }
        }
        return null;
    }

    private static long nz(Integer i) {
        return i == null ? -1 : i;
    }

    private <T> Result<T> run(ModelProfile p, String feature, Records.Source source, String caller,
                              BooleanSupplier cancelled, Budget budget, String prompt, Function<ModelClient, T> call,
                              Function<T, long[]> tokens, Function<T, String> answerText, Function<T, String> check,
                              List<String> domains) {
        long start = System.nanoTime();
        long deadline = budget.totalMs() > 0 ? System.currentTimeMillis() + budget.totalMs() : Long.MAX_VALUE;
        Optional<String> refusal;
        try {
            refusal = refusal(p, source, deadline);
        } catch (AiException e) {
            usage.record(new UsageLog.Entry(feature, p, source, null, null, 0, e.outcome(), e.getMessage(), caller, null,
                null, domains));
            throw e;
        }
        if (refusal.isPresent()) {
            usage.record(new UsageLog.Entry(feature, p, source, null, null, 0, "refused", refusal.get(), caller, null,
                null, domains));
            throw new AiException(Kind.REFUSED, refusal.get());
        }
        if (budget.skipIfDown() && downUntil.getOrDefault(p.id(), 0L) > System.currentTimeMillis()) {
            String msg = "Model " + p.name() + " failed or did not answer in the last " + DOWN_FOR_MS / 1000
                + " seconds, so it was not tried again yet.";
            usage.record(new UsageLog.Entry(feature, p, source, null, null, 0, "failed", msg, caller, null, null,
                domains));
            throw new AiException(Kind.FAILED, msg);
        }
        Built built;
        try {
            built = client(p);
        } catch (RuntimeException e) {
            String msg = "Cannot set up model " + p.name() + ": " + scrub(e.getMessage(), p);
            usage.record(new UsageLog.Entry(feature, p, source, null, null, 0, "failed", msg, caller, null, null,
                domains));
            throw new AiException(Kind.FAILED, msg);
        }
        boolean acquired = false;
        try {
            long wait = budget.slotWaitMs() > 0 ? budget.slotWaitMs() : p.timeoutSeconds() * 1000L;
            acquired = acquire(built.slots(), wait, cancelled);
            if (!acquired) {
                throw new AiException(Kind.BUSY, "Model " + p.name() + " is busy: " + p.maxConcurrent()
                    + " calls were already running in this process for " + wait / 1000.0 + " seconds. Try again "
                    + "shortly, or raise the profile's limit if its server can take more.");
            }
            T answer = withRetries(p, source, budget, deadline, () -> call.apply(built.client()), cancelled);
            String wrong = check.apply(answer);
            if (wrong != null) {
                throw new AiException(Kind.FAILED, wrong);
            }
            long ms = (System.nanoTime() - start) / 1_000_000;
            long[] t = tokens.apply(answer);
            downUntil.remove(p.id());
            usage.record(new UsageLog.Entry(feature, p, source, t[0] < 0 ? null : (int) t[0],
                t[1] < 0 ? null : (int) t[1], ms, "ok", null, caller, prompt, answerText.apply(answer), domains));
            return new Result<>(answer, p, ms);
        } catch (AiException e) {
            // Only interactive failures make searches skip the model: a slow background batch does not.
            if (budget.skipIfDown() && (e.kind() == Kind.TIMEOUT || e.unreachable())) {
                downUntil.put(p.id(), System.currentTimeMillis() + DOWN_FOR_MS);
            }
            usage.record(new UsageLog.Entry(feature, p, source, null, null, (System.nanoTime() - start) / 1_000_000,
                e.outcome(), e.getMessage(), caller, prompt, null, domains));
            throw e;
        } finally {
            if (acquired) {
                built.slots().release();
            }
        }
    }

    private static boolean acquire(Semaphore slots, long waitMs, BooleanSupplier cancelled) {
        long deadline = System.currentTimeMillis() + waitMs;
        try {
            do {
                if (cancelled.getAsBoolean()) {
                    throw new AiException(Kind.CANCELLED, "The call was cancelled.");
                }
                if (slots.tryAcquire(Math.min(CANCEL_POLL_MS, Math.max(1, deadline - System.currentTimeMillis())),
                    TimeUnit.MILLISECONDS)) {
                    return true;
                }
            } while (System.currentTimeMillis() < deadline);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException(Kind.CANCELLED, "The call was cancelled.");
        }
    }

    private Built client(ModelProfile p) {
        Built b = clients.get(p.id());
        if (b != null && b.version() == p.version()) {
            return b;
        }
        synchronized (clients) {
            b = clients.get(p.id());
            if (b != null && b.version() == p.version()) {
                return b;
            }
            ModelClient client = LangChainModelClient.create(p, profiles.apiKey(p.id()).orElse(null));
            // Keep the semaphore when the limit is unchanged, so calls already running still count.
            Semaphore slots = b != null && b.maxConcurrent() == p.maxConcurrent() ? b.slots()
                : new Semaphore(p.maxConcurrent(), true);
            Built fresh = new Built(p.version(), client, slots, p.maxConcurrent());
            clients.put(p.id(), fresh);
            downUntil.remove(p.id());
            return fresh;
        }
    }

    /** Drops a deleted or changed profile's client. */
    void forget(long profileId) {
        clients.remove(profileId);
        downUntil.remove(profileId);
    }

    @FunctionalInterface
    private interface Attempt<T> {
        T run();
    }

    /**
     * The address and egress checks: a metadata or link-local address, or a model the source's rule
     * or the server's rule does not allow. The name lookups (the metadata check, and whether a
     * container host name leads to a private address) run within the call's time (at most the
     * profile's timeout); one that does not finish is a failure to reach the server, not a refusal.
     */
    private Optional<String> refusal(ModelProfile p, Records.Source source, long deadline) {
        long limit = Math.min(p.timeoutSeconds() * 1000L, deadline - System.currentTimeMillis());
        CompletableFuture<Optional<String>> lookup = new CompletableFuture<>();
        Thread t = Thread.ofVirtual().name("kilnmark-ai-lookup").start(() -> {
            try {
                Optional<String> refused = hosts.forbidden(p.effectiveBaseUrl());
                // Looks up a container host name now, within this time limit; the egress check below reuses it.
                hosts.mayBeLocal(p.effectiveBaseUrl());
                lookup.complete(refused);
            } catch (Throwable e) {
                lookup.completeExceptionally(e);
            }
        });
        Optional<String> forbidden;
        try {
            forbidden = lookup.get(Math.max(1, limit), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            t.interrupt();
            throw new AiException(Kind.FAILED, "Looking up the address of model " + p.name() + " (" + p.effectiveBaseUrl()
                + ") took too long. Check the server's name service.", true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException(Kind.CANCELLED, "The call was cancelled.");
        } catch (ExecutionException e) {
            forbidden = Optional.empty(); // the call itself will say the server cannot be reached
        }
        return forbidden.or(() -> egress.refusal(p, source));
    }

    private <T> T withRetries(ModelProfile p, Records.Source source, Budget budget, long deadline, Attempt<T> attempt,
                              BooleanSupplier cancelled) {
        int retries = Math.max(0, config.ai().maxRetries());
        for (int n = 0; ; n++) {
            if (n > 0) {
                // Again before every retry: the rules or the address may have changed meanwhile.
                Optional<String> refused = refusal(p, source, deadline);
                if (refused.isPresent()) {
                    throw new AiException(Kind.REFUSED, refused.get());
                }
            }
            long attemptMs = Math.min(p.timeoutSeconds() * 1000L + 5000, deadline - System.currentTimeMillis());
            if (attemptMs <= 0) {
                throw new AiException(Kind.TIMEOUT, timeoutMessage(p, budget));
            }
            try {
                return once(p, budget, attemptMs, attempt, cancelled);
            } catch (AiException e) {
                if (e.kind() != Kind.TIMEOUT || !budget.retryTimeouts() || n >= retries) {
                    throw e;
                }
            } catch (RuntimeException e) {
                boolean timedOut = timedOut(e);
                if (n >= retries || !retryable(e) || (timedOut && !budget.retryTimeouts())) {
                    throw new AiException(timedOut ? Kind.TIMEOUT : Kind.FAILED, explain(e, p), unreachable(e));
                }
                LOG.debugf("Model %s: attempt %d failed (%s); retrying", p.name(), n + 1, e.getClass().getSimpleName());
            }
            backoff(n, deadline, cancelled);
        }
    }

    /** One attempt on a virtual thread, so a cancelled or overdue call is abandoned at once. */
    private static <T> T once(ModelProfile p, Budget budget, long limitMs, Attempt<T> attempt,
                              BooleanSupplier cancelled) {
        CompletableFuture<T> f = new CompletableFuture<>();
        Thread t = Thread.ofVirtual().name("kilnmark-ai-" + p.name()).start(() -> {
            try {
                f.complete(attempt.run());
            } catch (Throwable e) {
                f.completeExceptionally(e);
            }
        });
        // The client has the profile's own timeout; this also covers the budget and a server that trickles bytes.
        long deadline = System.currentTimeMillis() + limitMs;
        try {
            while (true) {
                if (cancelled.getAsBoolean()) {
                    t.interrupt();
                    throw new AiException(Kind.CANCELLED, "The call was cancelled.");
                }
                if (System.currentTimeMillis() > deadline) {
                    t.interrupt();
                    throw new AiException(Kind.TIMEOUT, timeoutMessage(p, budget));
                }
                try {
                    return f.get(CANCEL_POLL_MS, TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    // poll again
                }
            }
        } catch (InterruptedException e) {
            t.interrupt();
            Thread.currentThread().interrupt();
            throw new AiException(Kind.CANCELLED, "The call was cancelled.");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException r) {
                throw r;
            }
            throw new IllegalStateException(cause == null ? "unknown error" : cause.toString(), cause);
        }
    }

    private static void backoff(int attempt, long deadline, BooleanSupplier cancelled) {
        long ms = (long) (500 * Math.pow(2, attempt)) + ThreadLocalRandom.current().nextLong(250);
        long until = Math.min(deadline, System.currentTimeMillis() + ms);
        try {
            while (System.currentTimeMillis() < until) {
                if (cancelled.getAsBoolean()) {
                    throw new AiException(Kind.CANCELLED, "The call was cancelled.");
                }
                Thread.sleep(Math.min(CANCEL_POLL_MS, Math.max(1, until - System.currentTimeMillis())));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException(Kind.CANCELLED, "The call was cancelled.");
        }
    }

    static boolean retryable(Throwable e) {
        if (e instanceof RateLimitException || e instanceof InternalServerException || timedOut(e)) {
            return true;
        }
        Integer status = status(e);
        if (status != null) {
            return status == 408 || status == 429 || status >= 500;
        }
        return has(e, ConnectException.class);
    }

    private static boolean unreachable(Throwable e) {
        return has(e, ConnectException.class) || has(e, UnknownHostException.class)
            || has(e, UnresolvedAddressException.class) || has(e, HttpConnectTimeoutException.class);
    }

    private static boolean timedOut(Throwable e) {
        return e instanceof TimeoutException || has(e, HttpTimeoutException.class)
            || has(e, java.net.SocketTimeoutException.class);
    }

    private static Integer status(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpException h) {
                return h.statusCode();
            }
        }
        return null;
    }

    private static boolean has(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    private static String timeoutMessage(ModelProfile p, Budget budget) {
        return budget.totalMs() > 0 && budget.totalMs() < p.timeoutSeconds() * 1000L
            ? "Model " + p.name() + " did not answer within " + budget.totalMs() / 1000 + " seconds, the most a search "
                + "waits. Check the model server's load, or choose a faster model."
            : "Model " + p.name() + " did not answer within " + p.timeoutSeconds() + " seconds. Try again, raise the "
                + "profile's timeout, or choose a smaller model.";
    }

    /** A failure in plain English, saying what to check, for admins. Never includes the API key. */
    String explain(Throwable e, ModelProfile p) {
        String url = p.effectiveBaseUrl();
        Integer status = status(e);
        String msg;
        if (unreachable(e)) {
            msg = "Cannot reach " + url + ". Check the address, and that the model server is running and reachable "
                + "from the Kilnmark server.";
        } else if (timedOut(e)) {
            msg = timeoutMessage(p, Budget.BACKGROUND);
        } else if (e instanceof AuthenticationException || (status != null && (status == 401 || status == 403))) {
            msg = p.provider().label() + " refused the API key for model " + p.model() + ". Check the key, and that "
                + "it may use this model.";
        } else if (e instanceof ModelNotFoundException || (status != null && status == 404)) {
            msg = "The server at " + url + " does not know a model called " + p.model() + ", or the address is wrong. "
                + "Check the model name, and that the address ends where the API starts (often /v1).";
        } else if (e instanceof RateLimitException || (status != null && status == 429)) {
            msg = p.provider().label() + " is limiting requests (rate limit or quota). Wait a minute, or check the "
                + "account's quota.";
        } else if (e instanceof InternalServerException || (status != null && status >= 500)) {
            msg = "The model server failed" + (status == null ? "" : " (HTTP " + status + ")") + ". Check its log.";
        } else if (status != null) {
            msg = "The model server refused the request (HTTP " + status + "): " + shortMessage(e);
        } else {
            msg = "The call to model " + p.name() + " failed: " + shortMessage(e);
        }
        return scrub(msg, p);
    }

    private static String shortMessage(Throwable e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().strip();
        m = m.replaceAll("\\s+", " ");
        return m.length() > 300 ? m.substring(0, 300) + "…" : m;
    }

    /** Removes the profile's key from a message, should a server ever echo it. */
    private String scrub(String message, ModelProfile p) {
        if (message == null || !p.hasKey()) {
            return message;
        }
        try {
            String key = profiles.apiKey(p.id()).orElse(null);
            return key == null || key.length() < 4 ? message : message.replace(key, "[key]");
        } catch (RuntimeException e) {
            return message;
        }
    }
}

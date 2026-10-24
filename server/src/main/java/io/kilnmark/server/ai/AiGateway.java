package io.kilnmark.server.ai;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.List;
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
 * Every model call goes through here, in this order: find the feature's profile, check the egress
 * rule ({@link EgressPolicy}), wait for a free slot (the profile's {@code max_concurrent}), call
 * with the profile's timeout and up to {@code kilnmark.ai.max-retries} retries with backoff, stop
 * if cancelled, and write a usage record whatever happened.
 *
 * <p>Clients are built from the stored profile and rebuilt when its version changes, so adding or
 * changing a model needs no restart, in this process or any other.
 */
@ApplicationScoped
public class AiGateway {

    private static final Logger LOG = Logger.getLogger(AiGateway.class);
    private static final long CANCEL_POLL_MS = 200;

    private final ModelProfiles profiles;
    private final EgressPolicy egress;
    private final UsageLog usage;
    private final KilnmarkConfig config;
    private final Map<Long, Built> clients = new ConcurrentHashMap<>();

    /** A client for one version of a profile, with that profile's concurrency limit. */
    private record Built(int version, ModelClient client, Semaphore slots, int maxConcurrent) {
    }

    public AiGateway(ModelProfiles profiles, EgressPolicy egress, UsageLog usage, KilnmarkConfig config) {
        this.profiles = profiles;
        this.egress = egress;
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
     * metadata).
     *
     * @throws AiException when the feature is off, the call is refused, fails, times out or is cancelled
     */
    public Result<ModelClient.ChatAnswer> chat(AiFeature feature, Records.Source source, ModelClient.ChatCall call,
                                               String caller, BooleanSupplier cancelled) {
        ModelProfile p = routed(feature, source, caller);
        return chatWith(p, feature.id(), source, call, caller, cancelled);
    }

    /** Embeddings for {@code feature} (see {@link #chat}). */
    public Result<ModelClient.EmbedAnswer> embed(AiFeature feature, Records.Source source, List<String> texts,
                                                 String caller, BooleanSupplier cancelled) {
        ModelProfile p = routed(feature, source, caller);
        return embedWith(p, feature.id(), source, texts, caller, cancelled);
    }

    private ModelProfile routed(AiFeature feature, Records.Source source, String caller) {
        Optional<ModelProfile> p = profiles.route(feature);
        if (p.isEmpty()) {
            // Not recorded: an unrouted feature is off, not a failed call.
            throw new AiException(Kind.UNAVAILABLE, "No model is set for " + feature.label().toLowerCase(java.util.Locale.ROOT)
                + ". An admin can choose one under AI models.");
        }
        return p.get();
    }

    /** Chat with a given profile, under the same rules: used by routed features and by the profile test. */
    public Result<ModelClient.ChatAnswer> chatWith(ModelProfile p, String feature, Records.Source source,
                                                   ModelClient.ChatCall call, String caller, BooleanSupplier cancelled) {
        if (p.purpose() != Purpose.CHAT) {
            throw new AiException(Kind.FAILED, "Profile " + p.name() + " is an embedding model; this needs a chat model.");
        }
        String prompt = call.system() + "\n\n" + call.user();
        return run(p, feature, source, caller, cancelled, prompt, c -> c.chat(call),
            a -> new long[] {nz(a.tokensIn()), nz(a.tokensOut())}, ModelClient.ChatAnswer::text);
    }

    /** Embeddings with a given profile (see {@link #chatWith}). */
    public Result<ModelClient.EmbedAnswer> embedWith(ModelProfile p, String feature, Records.Source source,
                                                     List<String> texts, String caller, BooleanSupplier cancelled) {
        if (p.purpose() != Purpose.EMBEDDING) {
            throw new AiException(Kind.FAILED, "Profile " + p.name() + " is a chat model; this needs an embedding model.");
        }
        Result<ModelClient.EmbedAnswer> r = run(p, feature, source, caller, cancelled, String.join("\n---\n", texts),
            c -> c.embed(texts), a -> new long[] {nz(a.tokensIn()), -1}, a -> null);
        return r;
    }

    private static long nz(Integer i) {
        return i == null ? -1 : i;
    }

    private <T> Result<T> run(ModelProfile p, String feature, Records.Source source, String caller,
                              BooleanSupplier cancelled, String prompt, Function<ModelClient, T> call,
                              Function<T, long[]> tokens, Function<T, String> answerText) {
        long start = System.nanoTime();
        Optional<String> refusal = egress.refusal(p, source);
        if (refusal.isPresent()) {
            usage.record(new UsageLog.Entry(feature, p, source, null, null, 0, "refused", refusal.get(), caller, null,
                null));
            throw new AiException(Kind.REFUSED, refusal.get());
        }
        Built built;
        try {
            built = client(p);
        } catch (RuntimeException e) {
            String msg = "Cannot set up model " + p.name() + ": " + scrub(e.getMessage(), p);
            usage.record(new UsageLog.Entry(feature, p, source, null, null, 0, "failed", msg, caller, null, null));
            throw new AiException(Kind.FAILED, msg);
        }
        boolean acquired = false;
        try {
            acquired = acquire(built.slots(), p.timeoutSeconds() * 1000L, cancelled);
            if (!acquired) {
                throw new AiException(Kind.BUSY, "Model " + p.name() + " is busy: " + p.maxConcurrent()
                    + " calls were already running for " + p.timeoutSeconds() + " seconds. Try again shortly, or raise "
                    + "the profile's limit if its server can take more.");
            }
            T answer = withRetries(p, () -> call.apply(built.client()), cancelled);
            long ms = (System.nanoTime() - start) / 1_000_000;
            long[] t = tokens.apply(answer);
            usage.record(new UsageLog.Entry(feature, p, source, t[0] < 0 ? null : (int) t[0],
                t[1] < 0 ? null : (int) t[1], ms, "ok", null, caller, prompt, answerText.apply(answer)));
            return new Result<>(answer, p, ms);
        } catch (AiException e) {
            usage.record(new UsageLog.Entry(feature, p, source, null, null, (System.nanoTime() - start) / 1_000_000,
                e.outcome(), e.getMessage(), caller, prompt, null));
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
            while (System.currentTimeMillis() < deadline) {
                if (cancelled.getAsBoolean()) {
                    throw new AiException(Kind.CANCELLED, "The call was cancelled.");
                }
                if (slots.tryAcquire(Math.min(CANCEL_POLL_MS, Math.max(1, deadline - System.currentTimeMillis())),
                    TimeUnit.MILLISECONDS)) {
                    return true;
                }
            }
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
            return fresh;
        }
    }

    /** Drops a deleted profile's client. */
    void forget(long profileId) {
        clients.remove(profileId);
    }

    @FunctionalInterface
    private interface Attempt<T> {
        T run();
    }

    private <T> T withRetries(ModelProfile p, Attempt<T> attempt, BooleanSupplier cancelled) {
        int retries = Math.max(0, config.ai().maxRetries());
        for (int n = 0; ; n++) {
            try {
                return once(p, attempt, cancelled);
            } catch (AiException e) {
                throw e;
            } catch (RuntimeException e) {
                if (n >= retries || !retryable(e)) {
                    throw new AiException(timedOut(e) ? Kind.TIMEOUT : Kind.FAILED, explain(e, p));
                }
                LOG.debugf("Model %s: attempt %d failed (%s); retrying", p.name(), n + 1, e.getClass().getSimpleName());
                backoff(n, cancelled);
            }
        }
    }

    /** One attempt on a virtual thread, so a cancelled or overdue call is abandoned at once. */
    private static <T> T once(ModelProfile p, Attempt<T> attempt, BooleanSupplier cancelled) {
        CompletableFuture<T> f = new CompletableFuture<>();
        Thread t = Thread.ofVirtual().name("kilnmark-ai-" + p.name()).start(() -> {
            try {
                f.complete(attempt.run());
            } catch (Throwable e) {
                f.completeExceptionally(e);
            }
        });
        // The client has the same timeout; this is the backstop for a server that trickles bytes.
        long deadline = System.currentTimeMillis() + p.timeoutSeconds() * 1000L + 5000;
        try {
            while (true) {
                if (cancelled.getAsBoolean()) {
                    t.interrupt();
                    throw new AiException(Kind.CANCELLED, "The call was cancelled.");
                }
                if (System.currentTimeMillis() > deadline) {
                    t.interrupt();
                    throw new AiException(Kind.TIMEOUT, timeoutMessage(p));
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

    private static void backoff(int attempt, BooleanSupplier cancelled) {
        long ms = (long) (500 * Math.pow(2, attempt)) + ThreadLocalRandom.current().nextLong(250);
        long until = System.currentTimeMillis() + ms;
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
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static String timeoutMessage(ModelProfile p) {
        return "Model " + p.name() + " did not answer within " + p.timeoutSeconds() + " seconds. Try again, raise the "
            + "profile's timeout, or choose a smaller model.";
    }

    /** A failure in plain English, saying what to check. Never includes the API key. */
    String explain(Throwable e, ModelProfile p) {
        String url = p.effectiveBaseUrl();
        Integer status = status(e);
        String msg;
        if (has(e, ConnectException.class) || has(e, UnknownHostException.class)
            || has(e, UnresolvedAddressException.class) || has(e, HttpConnectTimeoutException.class)) {
            msg = "Cannot reach " + url + ". Check the address, and that the model server is running and reachable "
                + "from the Kilnmark server.";
        } else if (timedOut(e)) {
            msg = timeoutMessage(p);
        } else if (e instanceof AuthenticationException || status != null && (status == 401 || status == 403)) {
            msg = p.provider().label() + " refused the API key for model " + p.model() + ". Check the key, and that "
                + "it may use this model.";
        } else if (e instanceof ModelNotFoundException || status != null && status == 404) {
            msg = "The server at " + url + " does not know a model called " + p.model() + ", or the address is wrong. "
                + "Check the model name, and that the address ends where the API starts (often /v1).";
        } else if (e instanceof RateLimitException || status != null && status == 429) {
            msg = p.provider().label() + " is limiting requests (rate limit or quota). Wait a minute, or check the "
                + "account's quota.";
        } else if (e instanceof InternalServerException || status != null && status >= 500) {
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

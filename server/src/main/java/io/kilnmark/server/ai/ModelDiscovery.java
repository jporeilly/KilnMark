package io.kilnmark.server.ai;

import java.io.ByteArrayOutputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.UnresolvedAddressException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kilnmark.server.ai.AiViews.FoundModel;
import io.kilnmark.server.ai.AiViews.FoundServer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Finds model servers Kilnmark can reach and what they serve, for the AI models page. With no
 * address it tries a fixed list: Ollama and LM Studio on the machine running the container
 * ({@code host.containers.internal}, {@code host.docker.internal}) and on {@code localhost}, and
 * the bundled Compose services. With an address an admin typed, only that one.
 *
 * <p>Kilnmark does not look for a GPU itself: it may run in a container that cannot see one, and
 * the model server is what uses it. Ollama says how much of each loaded model is in GPU memory
 * ({@code /api/ps}), whatever the GPU's make; other servers are reported as not saying.
 *
 * <p>This is not a general HTTP fetcher. Every address passes {@link ModelHosts} first (no
 * metadata or link-local addresses; under the operator lock, only local hosts, and others are
 * reported without being called). Only GET requests to the model servers' own paths are made
 * ({@code /api/version}, {@code /api/tags}, {@code /api/ps}, {@code /v1/models}, {@code /health},
 * {@code /props}, {@code /api/v0/models}), with no key, short timeouts, no redirects followed and
 * at most {@value #MAX_BYTES} bytes read. Results are summarised: response bodies are never
 * returned.
 */
@ApplicationScoped
public class ModelDiscovery {

    /** What a candidate is expected to be; {@link #UNKNOWN} for an address an admin typed. */
    enum Kind {
        OLLAMA, LM_STUDIO, BUNDLED, UNKNOWN
    }

    /** An address to try: {@code root} is scheme, host, port and any path prefix, without {@code /v1}. */
    record Candidate(String label, Kind kind, String root, boolean fixed) {
    }

    /** Why a server could not be reached. */
    enum Why {
        REFUSED, TIMEOUT, UNKNOWN_HOST, OTHER
    }

    static final int MAX_BYTES = 512 * 1024;
    private static final int MAX_MODELS = 200;
    private static final int MAX_TEXT = 200;
    private static final String OLLAMA_HOST_ADVICE = "Ollama may be listening only on its own machine. Set "
        + "OLLAMA_HOST=0.0.0.0:11434 and restart Ollama, and keep port 11434 closed to other networks in the firewall.";
    private static final String LM_STUDIO_ADVICE = "LM Studio's server may be off, or serving only on its own "
        + "machine. In LM Studio, start the server and turn on Serve on Local Network, and keep port 1234 closed to "
        + "other networks in the firewall.";
    private static final String NOT_REPORTED = "GPU use not reported by this server";
    private static final Pattern EMBEDDING_NAME = Pattern.compile(
        "embed|(^|[/:_.-])(bge|gte|e5)([/:_.-]|$)|minilm|mxbai-embed|snowflake-arctic-embed");

    static final List<Candidate> CANDIDATES = List.of(
        new Candidate("Ollama on the machine running Kilnmark's container (Podman)", Kind.OLLAMA,
            "http://host.containers.internal:11434", true),
        new Candidate("Ollama on the machine running Kilnmark's container (Docker)", Kind.OLLAMA,
            "http://host.docker.internal:11434", true),
        new Candidate("Ollama on localhost", Kind.OLLAMA, "http://localhost:11434", true),
        new Candidate("LM Studio on the machine running Kilnmark's container (Podman)", Kind.LM_STUDIO,
            "http://host.containers.internal:1234", true),
        new Candidate("LM Studio on the machine running Kilnmark's container (Docker)", Kind.LM_STUDIO,
            "http://host.docker.internal:1234", true),
        new Candidate("LM Studio on localhost", Kind.LM_STUDIO, "http://localhost:1234", true),
        new Candidate("Bundled chat model (Compose service ai)", Kind.BUNDLED, "http://ai:8080", true),
        new Candidate("Bundled chat model on an NVIDIA GPU (Compose service ai-cuda)", Kind.BUNDLED,
            "http://ai-cuda:8080", true),
        new Candidate("Bundled embedding model (Compose service ai-embed)", Kind.BUNDLED, "http://ai-embed:8080",
            true));

    private final ModelHosts hosts;
    private final boolean inContainer;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final HttpClient client;
    private final ObjectMapper json = new ObjectMapper();

    @Inject
    public ModelDiscovery(ModelHosts hosts) {
        this(hosts, runningInContainer(), Duration.ofSeconds(1), Duration.ofSeconds(3));
    }

    ModelDiscovery(ModelHosts hosts, boolean inContainer, Duration connectTimeout, Duration readTimeout) {
        this.hosts = hosts;
        this.inContainer = inContainer;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        // Never through a proxy: these are servers on this machine or network.
        this.client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(connectTimeout)
            .version(HttpClient.Version.HTTP_1_1).executor(Executors.newVirtualThreadPerTaskExecutor()).build();
    }

    /** Podman writes /run/.containerenv, Docker /.dockerenv, and both set {@code container} in some images. */
    static boolean runningInContainer() {
        return Files.exists(Path.of("/run/.containerenv")) || Files.exists(Path.of("/.dockerenv"))
            || System.getenv("container") != null;
    }

    /** Tries the fixed list, or only {@code baseUrl} when given. */
    public AiViews.Discovery discover(String baseUrl) {
        List<Candidate> candidates = baseUrl == null || baseUrl.isBlank() ? CANDIDATES
            : List.of(new Candidate("The address you entered", Kind.UNKNOWN, root(baseUrl), false));
        return discover(candidates);
    }

    AiViews.Discovery discover(List<Candidate> candidates) {
        List<FoundServer> out = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<FoundServer>> futures = candidates.stream().map(c -> pool.submit(() -> probe(c))).toList();
            // Each request is bounded; this bounds a server that answers slowly to every request.
            long deadline = System.nanoTime() + (connectTimeout.plus(readTimeout)).multipliedBy(4).toNanos();
            for (int i = 0; i < candidates.size(); i++) {
                Candidate c = candidates.get(i);
                try {
                    out.add(futures.get(i).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
                } catch (TimeoutException e) {
                    futures.get(i).cancel(true);
                    out.add(missing(c, "not-found", "It answered too slowly to finish in time.", null));
                } catch (ExecutionException e) {
                    out.add(missing(c, "not-found", "It could not be checked: " + e.getCause().getClass().getSimpleName()
                        + ".", null));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    out.add(missing(c, "not-found", "Checking was interrupted.", null));
                }
            }
            pool.shutdownNow();
        }
        return new AiViews.Discovery(out, inContainer, hosts.locked());
    }

    /**
     * Where to probe for an address an admin typed: scheme, host, port and path, without a
     * trailing {@code /v1}. A missing scheme means http.
     *
     * @throws IllegalArgumentException for anything that is not a plain http or https address
     */
    static String root(String input) {
        String s = input.strip();
        if (!s.toLowerCase(Locale.ROOT).matches("https?://.*")) {
            if (s.contains("://")) {
                throw new IllegalArgumentException("baseUrl: use an http:// or https:// address");
            }
            s = "http://" + s;
        }
        URI u;
        try {
            u = new URI(s);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("baseUrl: not an address, for example http://host.containers.internal:11434");
        }
        if (u.getHost() == null || u.getRawUserInfo() != null || u.getRawQuery() != null || u.getRawFragment() != null) {
            throw new IllegalArgumentException("baseUrl: give only the scheme, host, port and path, for example "
                + "http://host.containers.internal:11434/v1");
        }
        String path = u.getRawPath() == null ? "" : u.getRawPath();
        path = path.replaceAll("/+$", "");
        if (path.toLowerCase(Locale.ROOT).endsWith("/v1")) {
            path = path.substring(0, path.length() - 3).replaceAll("/+$", "");
        }
        return u.getScheme().toLowerCase(Locale.ROOT) + "://" + u.getHost().toLowerCase(Locale.ROOT)
            + (u.getPort() >= 0 ? ":" + u.getPort() : "") + path;
    }

    /** One candidate, start to finish. Never throws for a server's failure: it is described instead. */
    FoundServer probe(Candidate c) {
        String base = c.root() + "/v1";
        String host = ModelHosts.host(base);
        Optional<String> refused = hosts.forbidden(base);
        if (refused.isPresent()) {
            return missing(c, "refused", "Not checked. " + refused.get(), null);
        }
        if (!hosts.mayBeLocal(base)) {
            return missing(c, "not-local", "Not checked: this server allows only local models "
                + "(kilnmark.ai.hosted-models=forbidden), and " + host + " is not in its list of local hosts.",
                "To use a model server at " + host + ", whoever runs Kilnmark adds " + host
                    + " to kilnmark.ai.local-hosts (KILNMARK_AI_LOCAL_HOSTS), keeping the entries already there.");
        }
        try {
            Fetched version = null;
            if (c.kind() == Kind.OLLAMA || c.kind() == Kind.UNKNOWN) {
                version = get(c.root() + "/api/version");
                JsonNode v = version.ok() ? version.json() : null;
                if (v != null && v.path("version").isTextual()) {
                    return ollama(c, text(v.path("version").asText()));
                }
                if (c.kind() == Kind.OLLAMA) {
                    return missing(c, "not-found", notAModelServer(version, "/api/version", "Ollama"), null);
                }
            }
            return openAiCompatible(c);
        } catch (Unreachable e) {
            return missing(c, "not-found", e.reason, guidance(c, e.why));
        }
    }

    private FoundServer ollama(Candidate c, String version) throws Unreachable {
        Fetched tags = get(c.root() + "/api/tags");
        if (!tags.ok() || tags.json() == null || !tags.json().path("models").isArray()) {
            return missing(c, "not-found", notAModelServer(tags, "/api/tags", "Ollama"), null);
        }
        Map<String, JsonNode> loaded = new HashMap<>();
        boolean psKnown = false;
        Fetched ps = tryGet(c.root() + "/api/ps");
        if (ps != null && ps.ok() && ps.json() != null && ps.json().path("models").isArray()) {
            psKnown = true;
            for (JsonNode m : ps.json().path("models")) {
                loaded.put(m.path("name").asText(m.path("model").asText()), m);
                loaded.putIfAbsent(m.path("model").asText(), m);
            }
        }
        Map<String, FoundModel> models = new LinkedHashMap<>();
        for (JsonNode m : tags.json().path("models")) {
            if (models.size() >= MAX_MODELS) {
                break;
            }
            String name = text(m.path("name").asText(m.path("model").asText()));
            if (name.isEmpty()) {
                continue;
            }
            JsonNode d = m.path("details");
            List<String> families = new ArrayList<>();
            d.path("families").forEach(f -> families.add(f.asText()));
            String family = textOrNull(d.path("family"));
            boolean embedding = looksLikeEmbedding(name, family, families);
            long size = m.path("size").asLong(0);
            JsonNode run = loaded.get(name);
            Gpu g = run != null ? gpu(run.path("size").asLong(size), run.path("size_vram").asLong(0))
                : psKnown ? Gpu.NOT_LOADED : Gpu.UNKNOWN;
            Integer context = run != null && run.path("context_length").canConvertToInt()
                && run.path("context_length").asInt() > 0 ? run.path("context_length").asInt() : null;
            models.put(name, new FoundModel(name, size > 0 ? size : null, family, textOrNull(d.path("parameter_size")),
                textOrNull(d.path("quantization_level")), embedding, run != null ? Boolean.TRUE : psKnown ? false : null,
                g.state(), g.percent(), run != null ? run.path("size_vram").asLong(0) : null, g.text(), context));
        }
        String guidance = models.isEmpty() ? "Ollama has no models yet. Pull one on that machine, for example "
            + "ollama pull qwen2.5:1.5b (chat) and ollama pull nomic-embed-text (embeddings)." : null;
        return new FoundServer(c.label(), "Ollama " + version, c.root(), c.root() + "/v1", "found", null, guidance,
            suggestLocal(c), psKnown ? null : "Ollama did not say which models are loaded (/api/ps).", psKnown,
            List.copyOf(models.values()));
    }

    private FoundServer openAiCompatible(Candidate c) throws Unreachable {
        Fetched f = get(c.root() + "/v1/models");
        if (!f.ok() || f.json() == null || !f.json().path("data").isArray()) {
            String guidance = f.status() == 401 || f.status() == 403 ? "Kilnmark sends no API key when looking. "
                + "Add the model with its address and key in the form below, then press Test." : null;
            return missing(c, "not-found", notAModelServer(f, "/v1/models", null), guidance);
        }
        JsonNode data = f.json().path("data");
        boolean llama = false;
        boolean vllm = false;
        for (JsonNode m : data) {
            String owner = m.path("owned_by").asText("").toLowerCase(Locale.ROOT);
            llama |= owner.equals("llamacpp");
            vllm |= owner.equals("vllm");
        }
        String software;
        List<String> details = new ArrayList<>();
        Map<String, JsonNode> lmStudio = Map.of();
        if (llama) {
            software = "llama-server";
            Fetched health = tryGet(c.root() + "/health");
            if (health != null && health.status() == 503) {
                details.add("It is still loading its model.");
            }
            Fetched props = tryGet(c.root() + "/props");
            if (props != null && props.ok() && props.json() != null) {
                JsonNode p = props.json();
                JsonNode ctx = p.path("default_generation_settings").path("n_ctx");
                if (!ctx.canConvertToInt() || ctx.asInt() <= 0) {
                    ctx = p.path("n_ctx");
                }
                if (ctx.canConvertToInt() && ctx.asInt() > 0) {
                    details.add("Context size " + ctx.asInt() + " tokens.");
                }
                String file = p.path("model_path").asText("");
                if (!file.isBlank()) {
                    details.add("Model file " + text(file.substring(Math.max(file.lastIndexOf('/'),
                        file.lastIndexOf('\\')) + 1)) + ".");
                }
            }
        } else if (vllm) {
            software = "vLLM";
        } else {
            Fetched lm = tryGet(c.root() + "/api/v0/models");
            if (lm != null && lm.ok() && lm.json() != null && lm.json().path("data").isArray()) {
                software = "LM Studio";
                lmStudio = new HashMap<>();
                for (JsonNode m : lm.json().path("data")) {
                    lmStudio.put(m.path("id").asText(), m);
                }
            } else {
                software = c.kind() == Kind.LM_STUDIO ? "LM Studio" : "OpenAI-compatible server";
            }
        }
        List<FoundModel> models = new ArrayList<>();
        for (JsonNode m : data) {
            if (models.size() >= MAX_MODELS) {
                break;
            }
            String id = text(m.path("id").asText(""));
            if (id.isEmpty()) {
                continue;
            }
            JsonNode meta = m.path("meta"); // llama-server
            JsonNode lm = lmStudio.getOrDefault(id, json.missingNode());
            long size = meta.path("size").asLong(0);
            long params = meta.path("n_params").asLong(0);
            String type = lm.path("type").asText("");
            boolean embedding = type.equals("embeddings") || type.isEmpty() && looksLikeEmbedding(id, null, List.of());
            String state = lm.path("state").asText("");
            Integer context = lm.path("max_context_length").canConvertToInt() && lm.path("max_context_length").asInt() > 0
                ? lm.path("max_context_length").asInt() : null;
            models.add(new FoundModel(id, size > 0 ? size : null, textOrNull(lm.path("arch")),
                params > 0 ? parameters(params) : null, textOrNull(lm.path("quantization")), embedding,
                state.equals("loaded") ? Boolean.TRUE : state.equals("not-loaded") ? Boolean.FALSE : null,
                Gpu.UNKNOWN.state(), null, null, Gpu.UNKNOWN.text(), context));
        }
        return new FoundServer(c.label(), software, c.root(), c.root() + "/v1", "found", null, null, suggestLocal(c),
            details.isEmpty() ? null : String.join(" ", details), false, models);
    }

    /** Fixed candidates are on this machine or in this Compose project; a typed address only if on the local list. */
    private boolean suggestLocal(Candidate c) {
        return c.fixed() || hosts.isLocalHost(ModelHosts.host(c.root() + "/v1"));
    }

    private FoundServer missing(Candidate c, String outcome, String problem, String guidance) {
        return new FoundServer(c.label(), null, c.root(), c.root() + "/v1", outcome, problem, guidance, false, null,
            false, List.of());
    }

    /** Plain words for an answer that was not what a model server sends. */
    static String notAModelServer(Fetched f, String path, String expected) {
        String what = expected == null ? "a model server" : expected;
        if (f.tooBig()) {
            return "Something answered at " + path + " with more than " + MAX_BYTES / 1024 + " KB, which is not what "
                + what + " sends, so it was not read.";
        }
        if (f.status() >= 300 && f.status() < 400) {
            return "Something answered at " + path + " with a redirect (HTTP " + f.status() + "), which Kilnmark does "
                + "not follow. Enter the address it should use instead.";
        }
        if (f.status() == 401 || f.status() == 403) {
            return "The server at " + path + " asks for an API key (HTTP " + f.status() + ").";
        }
        if (f.status() >= 400) {
            return "Something is listening here, but answered HTTP " + f.status() + " at " + path + ", so it is not "
                + what + ", or the address has the wrong path or port.";
        }
        return "Something is listening here, but it did not answer like " + what + " at " + path + ".";
    }

    /** What to do about an address that could not be reached, where there is something useful to say. */
    String guidance(Candidate c, Why why) {
        String host = ModelHosts.host(c.root() + "/v1");
        int port = URI.create(c.root()).getPort();
        Kind kind = c.kind() != Kind.UNKNOWN ? c.kind()
            : port == 11434 ? Kind.OLLAMA : port == 1234 ? Kind.LM_STUDIO : Kind.UNKNOWN;
        boolean loopback = host.equals("localhost") || host.startsWith("127.") || host.equals("::1");
        boolean hostName = host.equals("host.containers.internal") || host.equals("host.docker.internal");
        if (loopback && inContainer && why != Why.UNKNOWN_HOST) {
            return "Kilnmark runs in a container, where localhost is the container itself, not the computer it runs "
                + "on. Use host.containers.internal (Podman) or host.docker.internal (Docker) instead.";
        }
        if (hostName && why == Why.UNKNOWN_HOST) {
            if (!inContainer) {
                return "Kilnmark is not running in a container, so this name is not known here. Use localhost for a "
                    + "model server on this computer.";
            }
            return host.equals("host.containers.internal")
                ? "This name is known only inside Podman containers. With Docker, use host.docker.internal."
                : "This name is known in Docker Desktop, and in Compose when the service has extra_hosts "
                    + "host.docker.internal:host-gateway (Kilnmark's compose.yaml sets it). With Podman, use "
                    + "host.containers.internal.";
        }
        if (kind == Kind.BUNDLED) {
            return why == Why.UNKNOWN_HOST
                ? "This is a service of Kilnmark's own Compose stack, and it is not running (or Kilnmark is not in the "
                    + "same Compose project). Start it with podman compose --profile ai up -d, or --profile ai-cuda "
                    + "for an NVIDIA GPU."
                : "The service may still be starting or loading its model. Try again in a minute.";
        }
        if (why != Why.REFUSED && why != Why.TIMEOUT) {
            return null;
        }
        if (hostName || !loopback && c.kind() == Kind.UNKNOWN) {
            return switch (kind) {
                case OLLAMA -> OLLAMA_HOST_ADVICE;
                case LM_STUDIO -> LM_STUDIO_ADVICE;
                default -> "The model server may be listening only on its own machine (127.0.0.1). Make it listen on "
                    + "all addresses (0.0.0.0), and keep its port closed to other networks in the firewall. Check "
                    + "the address and port too.";
            };
        }
        if (loopback && why == Why.REFUSED) {
            return switch (kind) {
                case OLLAMA -> "Ollama is not running on this computer, or listens on another port.";
                case LM_STUDIO -> "LM Studio's server is not running on this computer, or listens on another port.";
                default -> null;
            };
        }
        return null;
    }

    /** How much of a loaded model Ollama holds in GPU memory. */
    record Gpu(String state, Integer percent, String text) {
        static final Gpu NOT_LOADED = new Gpu("not-loaded", null, "Not loaded yet — GPU use shows once it has run");
        static final Gpu UNKNOWN = new Gpu("unknown", null, NOT_REPORTED);
    }

    /** On GPU (nearly all in GPU memory), split, or CPU only, from Ollama's size and size_vram. */
    static Gpu gpu(long size, long vram) {
        if (vram <= 0) {
            return new Gpu("cpu", 0, "CPU only");
        }
        if (size <= 0 || vram >= size * 0.95) {
            return new Gpu("gpu", 100, "On GPU · " + bytes(vram) + " in GPU memory");
        }
        int pct = (int) Math.max(1, Math.min(99, Math.round(vram * 100.0 / size)));
        return new Gpu("split", pct, "Split · " + pct + "% on GPU");
    }

    /** Decimal units, as model servers show sizes: 5.1 GB. */
    static String bytes(long n) {
        if (n >= 1_000_000_000L) {
            return String.format(Locale.ROOT, "%.1f GB", n / 1e9);
        }
        return String.format(Locale.ROOT, "%d MB", Math.max(1, Math.round(n / 1e6)));
    }

    /** A parameter count as model names give it: 1.5B, 494M. */
    static String parameters(long n) {
        return n >= 1_000_000_000L ? String.format(Locale.ROOT, "%.1fB", n / 1e9)
            : String.format(Locale.ROOT, "%dM", Math.round(n / 1e6));
    }

    /** Whether a model is for embeddings, by its name or (Ollama) its family: BERT-style models are. */
    static boolean looksLikeEmbedding(String name, String family, List<String> families) {
        if (EMBEDDING_NAME.matcher(name.toLowerCase(Locale.ROOT)).find()) {
            return true;
        }
        List<String> all = new ArrayList<>(families);
        if (family != null) {
            all.add(family);
        }
        return all.stream().map(f -> f.toLowerCase(Locale.ROOT)).anyMatch(f -> f.endsWith("bert"));
    }

    private static String text(String s) {
        String t = s == null ? "" : s.strip().replaceAll("\\p{Cntrl}", "");
        return t.length() > MAX_TEXT ? t.substring(0, MAX_TEXT) : t;
    }

    private static String textOrNull(JsonNode n) {
        String t = n.isValueNode() ? text(n.asText()) : "";
        return t.isEmpty() ? null : t;
    }

    /** An answer: its status and, when it was JSON within the size limit, the parsed body. */
    record Fetched(int status, JsonNode json, boolean tooBig) {
        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /** No answer at all: refused, timed out, unknown name. */
    static final class Unreachable extends Exception {
        private static final long serialVersionUID = 1L;
        final Why why;
        final String reason;

        Unreachable(Why why, String reason) {
            super(reason, null, false, false);
            this.why = why;
            this.reason = reason;
        }
    }

    /** Like {@link #get}, but an extra that cannot be fetched is just missing. */
    private Fetched tryGet(String url) {
        try {
            return get(url);
        } catch (Unreachable e) {
            return null;
        }
    }

    Fetched get(String url) throws Unreachable {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(readTimeout).GET()
            .header("Accept", "application/json").header("User-Agent", "Kilnmark").build();
        CompletableFuture<HttpResponse<byte[]>> call = client.sendAsync(request, info -> new Capped());
        HttpResponse<byte[]> r;
        try {
            r = call.get(connectTimeout.plus(readTimeout).toMillis() + 250, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            call.cancel(true);
            throw new Unreachable(Why.TIMEOUT, "It connected but did not answer within " + seconds(readTimeout) + ".");
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new Unreachable(Why.OTHER, "Checking was interrupted.");
        } catch (ExecutionException e) {
            throw unreachable(e.getCause());
        }
        byte[] body = r.body();
        if (body == null) {
            return new Fetched(r.statusCode(), null, true);
        }
        JsonNode node = null;
        try {
            node = body.length == 0 ? null : json.readTree(body);
        } catch (java.io.IOException e) {
            // not JSON: not a model server's answer
        }
        return new Fetched(r.statusCode(), node != null && node.isContainerNode() ? node : null, false);
    }

    private Unreachable unreachable(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof UnresolvedAddressException || t instanceof UnknownHostException) {
                return new Unreachable(Why.UNKNOWN_HOST, "This host name is not known here.");
            }
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpConnectTimeoutException) {
                return new Unreachable(Why.TIMEOUT, "Nothing answered within " + seconds(connectTimeout)
                    + " (the connection timed out).");
            }
            if (t instanceof HttpTimeoutException) {
                return new Unreachable(Why.TIMEOUT, "It connected but did not answer within " + seconds(readTimeout)
                    + ".");
            }
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConnectException) {
                return new Unreachable(Why.REFUSED, "Nothing is listening there (connection refused).");
            }
        }
        return new Unreachable(Why.OTHER, "It could not be reached (" + e.getClass().getSimpleName() + ").");
    }

    private static String seconds(Duration d) {
        long ms = d.toMillis();
        return ms % 1000 == 0 ? ms / 1000 + (ms == 1000 ? " second" : " seconds") : ms + " ms";
    }

    /** Collects a body up to {@link #MAX_BYTES}; past that it stops reading and gives null. */
    private static final class Capped implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override
        public CompletableFuture<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer b : items) {
                if (out.size() + b.remaining() > MAX_BYTES) {
                    subscription.cancel();
                    result.complete(null);
                    return;
                }
                byte[] chunk = new byte[b.remaining()];
                b.get(chunk);
                out.writeBytes(chunk);
            }
        }

        @Override
        public void onError(Throwable t) {
            result.completeExceptionally(t);
        }

        @Override
        public void onComplete() {
            result.complete(out.toByteArray());
        }
    }
}

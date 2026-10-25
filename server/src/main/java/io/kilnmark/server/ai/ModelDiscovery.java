package io.kilnmark.server.ai;

import java.io.ByteArrayOutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Finds model servers Kilnmark can reach and what they serve, for the AI models page. With no
 * address it tries a fixed list: Ollama and LM Studio on {@code localhost} and, in a container,
 * on the machine running it ({@code host.containers.internal}, {@code host.docker.internal}),
 * and the bundled Compose services. With an address an admin typed, only that one.
 *
 * <p>Kilnmark does not look for a GPU itself: it may run in a container that cannot see one, and
 * the model server is what uses it. Ollama says how much of each loaded model is in GPU memory
 * ({@code /api/ps}), whatever the GPU's make; other servers are reported as not saying.
 *
 * <p>This is not a general HTTP fetcher. Every address passes {@link ModelHosts} first: under the
 * operator lock a host not on the local list is reported without being looked up or called; the
 * name is looked up within a time limit; metadata and link-local addresses are refused. Only the
 * model servers' own paths are requested ({@code GET /api/version}, {@code /api/tags},
 * {@code /api/ps}, {@code /v1/models}, {@code /health}, {@code /props}, {@code /api/v0/models},
 * and {@code POST /api/show} for Ollama's embedding models), with no key, short timeouts, no
 * redirects followed, no proxy and at most {@value #MAX_BYTES} bytes read. Results are
 * summarised: response bodies are never returned.
 */
@ApplicationScoped
public class ModelDiscovery implements AutoCloseable {

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

    /** A probe's result, and where it really went (first address and port), to spot one server under two names. */
    record Probe(FoundServer server, String key) {
    }

    static final int MAX_BYTES = 512 * 1024;
    private static final int MAX_MODELS = 200;
    private static final int MAX_SHOW = 8;
    private static final int MAX_TEXT = 200;
    private static final Set<String> BUNDLED_NAMES = Set.of("ai", "ai-cuda", "ai-embed");
    private static final String OLLAMA_HOST_ADVICE = "Ollama may be listening only on its own machine. Set "
        + "OLLAMA_HOST=0.0.0.0:11434 and restart Ollama, and keep port 11434 closed to other networks in the firewall.";
    private static final String LM_STUDIO_ADVICE = "LM Studio's server may be off, or serving only on its own "
        + "machine. In LM Studio, start the server and turn on Serve on Local Network, and keep port 1234 closed to "
        + "other networks in the firewall.";
    private static final String NOT_REPORTED = "GPU use not reported by this server";
    private static final Pattern EMBEDDING_NAME = Pattern.compile(
        "embed|(^|[/:_.-])(bge|gte|e5)([/:_.-]|$)|minilm|mxbai-embed|snowflake-arctic-embed");
    private static final Pattern PATH = Pattern.compile("[A-Za-z0-9._~/-]*");

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
    private final Duration overall;
    private final ExecutorService httpThreads = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient client;
    private final ObjectMapper json = new ObjectMapper();

    @Inject
    public ModelDiscovery(ModelHosts hosts) {
        this(hosts, Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(12));
    }

    ModelDiscovery(ModelHosts hosts, Duration connectTimeout, Duration readTimeout, Duration overall) {
        this.hosts = hosts;
        this.inContainer = hosts.inContainer();
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.overall = overall;
        // Never through a proxy: these are servers on this machine or network.
        this.client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(connectTimeout)
            .version(HttpClient.Version.HTTP_1_1).executor(httpThreads).build();
    }

    @Override
    @PreDestroy
    public void close() {
        client.shutdownNow();
        httpThreads.shutdownNow();
    }

    /** Tries the usual places, or only {@code baseUrl} when given. */
    public AiViews.Discovery discover(String baseUrl) {
        List<Candidate> candidates = baseUrl == null || baseUrl.isBlank() ? usual()
            : List.of(new Candidate("The address you entered", Kind.UNKNOWN, root(baseUrl), false));
        return discover(candidates);
    }

    /** Outside a container only localhost: the container and Compose names mean nothing there, and are not looked up. */
    List<Candidate> usual() {
        return inContainer ? CANDIDATES
            : CANDIDATES.stream().filter(c -> ModelHosts.host(c.root() + "/v1").equals("localhost")).toList();
    }

    AiViews.Discovery discover(List<Candidate> candidates) {
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        List<Probe> probes = new ArrayList<>();
        try {
            List<Future<Probe>> futures = candidates.stream().map(c -> pool.submit(() -> probe(c))).toList();
            long deadline = System.nanoTime() + overall.toNanos();
            for (int i = 0; i < candidates.size(); i++) {
                Candidate c = candidates.get(i);
                try {
                    probes.add(futures.get(i).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
                } catch (TimeoutException e) {
                    futures.get(i).cancel(true);
                    probes.add(new Probe(missing(c, "not-found", "It answered too slowly to finish in time.", null), null));
                } catch (ExecutionException e) {
                    probes.add(new Probe(missing(c, "not-found", "It could not be checked: "
                        + e.getCause().getClass().getSimpleName() + ".", null), null));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    probes.add(new Probe(missing(c, "not-found", "Checking was interrupted.", null), null));
                }
            }
        } finally {
            pool.shutdownNow(); // never waits: a stuck lookup or read is abandoned, not awaited
        }
        return new AiViews.Discovery(merge(probes), inContainer, hosts.locked());
    }

    /** One server found under two names (both container host names, usually) is listed once. */
    private static List<FoundServer> merge(List<Probe> probes) {
        Map<String, Integer> firstAt = new HashMap<>();
        List<FoundServer> out = new ArrayList<>();
        for (Probe p : probes) {
            FoundServer s = p.server();
            Integer at = s.outcome().equals("found") && p.key() != null ? firstAt.get(p.key()) : null;
            if (at == null) {
                if (s.outcome().equals("found") && p.key() != null) {
                    firstAt.put(p.key(), out.size());
                }
                out.add(s);
                continue;
            }
            FoundServer f = out.get(at);
            String also = "Also reachable at " + s.baseUrl() + ".";
            out.set(at, new FoundServer(f.label(), f.server(), f.address(), f.baseUrl(), f.outcome(), f.problem(),
                f.guidance(), f.suggestLocal(), f.detail() == null ? also : f.detail() + " " + also, f.gpuReported(),
                f.models()));
        }
        return out;
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
        if (u.getPort() != -1 && (u.getPort() < 1 || u.getPort() > 65535)) {
            throw new IllegalArgumentException("baseUrl: the port must be between 1 and 65535");
        }
        String path = u.getRawPath() == null ? "" : u.getRawPath();
        boolean dotSegment = Arrays.stream(path.split("/")).anyMatch(seg -> seg.equals(".") || seg.equals(".."));
        if (!PATH.matcher(path).matches() || dotSegment) {
            throw new IllegalArgumentException("baseUrl: the path may hold only letters, digits and . _ ~ - /, with no "
                + ". or .. parts, for example http://host.containers.internal:11434/v1");
        }
        path = path.replaceAll("/+$", "");
        if (path.toLowerCase(Locale.ROOT).endsWith("/v1")) {
            path = path.substring(0, path.length() - 3).replaceAll("/+$", "");
        }
        return u.getScheme().toLowerCase(Locale.ROOT) + "://" + u.getHost().toLowerCase(Locale.ROOT)
            + (u.getPort() >= 0 ? ":" + u.getPort() : "") + path;
    }

    /** One candidate, start to finish. Never throws for a server's failure: it is described instead. */
    Probe probe(Candidate c) {
        String base = c.root() + "/v1";
        String host = ModelHosts.host(base);
        // Under the lock, a host that is not on the list is neither looked up nor called.
        if (hosts.locked() && !hosts.listedLocal(host)) {
            return new Probe(missing(c, "not-local", "Not checked: this server allows only local models "
                + "(kilnmark.ai.hosted-models=forbidden), and " + host + " is not in its list of local hosts.",
                addToLocalHosts(host)), null);
        }
        Optional<String> named = hosts.forbidden(host, new InetAddress[0]);
        if (named.isPresent()) {
            return new Probe(missing(c, "refused", "Not checked. " + named.get(), null), null);
        }
        InetAddress[] addresses;
        try {
            addresses = lookup(host);
        } catch (Unreachable e) {
            return new Probe(missing(c, "not-found", e.reason, guidance(c, e.why)), null);
        }
        Optional<String> refused = hosts.forbidden(host, addresses);
        if (refused.isPresent()) {
            return new Probe(missing(c, "refused", "Not checked. " + refused.get(), null), null);
        }
        if (!hosts.mayBeLocal(host, addresses)) {
            return new Probe(missing(c, "not-local", "Not checked: " + host + " leads to " + addresses[0].getHostAddress()
                + ", which is neither this machine nor a private network, so this server does not count it as local.",
                null), null);
        }
        boolean local = suggestLocal(c, host, addresses);
        String key = addresses.length == 0 ? null
            : addresses[0].getHostAddress() + ":" + URI.create(c.root()).getPort() + URI.create(c.root()).getRawPath();
        try {
            if (c.kind() == Kind.OLLAMA || c.kind() == Kind.UNKNOWN) {
                Fetched version = get(c.root() + "/api/version");
                JsonNode v = version.ok() ? version.json() : null;
                if (v != null && v.path("version").isTextual()) {
                    return new Probe(ollama(c, text(v.path("version").asText()), local), key);
                }
                if (c.kind() == Kind.OLLAMA) {
                    return new Probe(missing(c, "not-found", notAModelServer(version, "/api/version", "Ollama"),
                        null), null);
                }
                // A typed address that is not Ollama: perhaps an OpenAI-compatible server.
            }
            return new Probe(openAiCompatible(c, local), key);
        } catch (Unreachable e) {
            return new Probe(missing(c, "not-found", e.reason, guidance(c, e.why)), null);
        }
    }

    private static String addToLocalHosts(String host) {
        return "If a model server at " + host + " runs on your own network, whoever runs Kilnmark can add " + host
            + " to kilnmark.ai.local-hosts (KILNMARK_AI_LOCAL_HOSTS), keeping the entries already there. Only do this "
            + "if it runs on your own network.";
    }

    /** The host's addresses, looked up within the connect timeout (doubled), so a stalled name service cannot hold us. */
    private InetAddress[] lookup(String host) throws Unreachable {
        CompletableFuture<InetAddress[]> f = new CompletableFuture<>();
        Thread t = Thread.ofVirtual().name("kilnmark-discover-lookup").start(() -> {
            try {
                f.complete(hosts.resolve(host));
            } catch (Throwable e) {
                f.completeExceptionally(e);
            }
        });
        Duration limit = connectTimeout.multipliedBy(2);
        try {
            return f.get(limit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            t.interrupt();
            throw new Unreachable(Why.OTHER, "Looking up this host name took longer than " + seconds(limit)
                + ". Check the name service of the machine running Kilnmark.");
        } catch (InterruptedException e) {
            t.interrupt();
            Thread.currentThread().interrupt();
            throw new Unreachable(Why.OTHER, "Checking was interrupted.");
        } catch (ExecutionException e) {
            throw new Unreachable(Why.UNKNOWN_HOST, "This host name is not known here.");
        }
    }

    private FoundServer ollama(Candidate c, String version, boolean local) throws Unreachable {
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
        int shown = 0;
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
            Integer context = positive(run == null ? null : run.path("context_length"));
            Integer dimensions = null;
            if (embedding && shown < MAX_SHOW) {
                // What the form needs for an embedding model: its size of vector, and how much text it takes.
                shown++;
                JsonNode info = show(c.root(), name);
                if (info != null) {
                    for (Map.Entry<String, JsonNode> e : info.properties()) {
                        if (e.getKey().endsWith(".embedding_length")) {
                            dimensions = positive(e.getValue());
                        } else if (e.getKey().endsWith(".context_length") && context == null) {
                            context = positive(e.getValue());
                        }
                    }
                }
            }
            models.put(name, new FoundModel(name, size > 0 ? size : null, family, textOrNull(d.path("parameter_size")),
                textOrNull(d.path("quantization_level")), embedding, run != null ? Boolean.TRUE : psKnown ? false : null,
                g.state(), g.percent(), run != null ? run.path("size_vram").asLong(0) : null, g.text(), context,
                dimensions));
        }
        String guidance = models.isEmpty() ? "Ollama has no models yet. Pull one on that machine, for example "
            + "ollama pull qwen2.5:1.5b (chat) and ollama pull nomic-embed-text (embeddings)." : null;
        return new FoundServer(c.label(), "Ollama " + version, c.root(), c.root() + "/v1", "found", null, guidance,
            local, psKnown ? null : "Ollama did not say which models are loaded (/api/ps).", psKnown,
            List.copyOf(models.values()));
    }

    /** Ollama's {@code model_info} for one model ({@code POST /api/show}), or null. */
    private JsonNode show(String root, String model) {
        try {
            Fetched f = send(HttpRequest.newBuilder(URI.create(root + "/api/show")).timeout(readTimeout)
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .header("User-Agent", "Kilnmark")
                .POST(HttpRequest.BodyPublishers.ofString(json.createObjectNode().put("model", model).toString()))
                .build());
            JsonNode info = f.ok() && f.json() != null ? f.json().path("model_info") : null;
            return info != null && info.isObject() ? info : null;
        } catch (Unreachable e) {
            return null;
        }
    }

    private FoundServer openAiCompatible(Candidate c, boolean local) throws Unreachable {
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
        Integer serverContext = null;
        if (llama) {
            software = "llama-server";
            Fetched health = tryGet(c.root() + "/health");
            if (health != null && health.status() == 503) {
                details.add("It is still loading its model.");
            }
            Fetched props = tryGet(c.root() + "/props");
            if (props != null && props.ok() && props.json() != null) {
                JsonNode p = props.json();
                serverContext = positive(p.path("default_generation_settings").path("n_ctx"));
                if (serverContext == null) {
                    serverContext = positive(p.path("n_ctx"));
                }
                if (serverContext != null) {
                    details.add("Context size " + serverContext + " tokens.");
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
            Integer context = positive(lm.path("max_context_length"));
            if (context == null) {
                context = positive(m.path("max_model_len")); // vLLM
            }
            if (context == null) {
                context = serverContext;
            }
            Integer dimensions = embedding ? positive(meta.path("n_embd")) : null;
            models.add(new FoundModel(id, size > 0 ? size : null, textOrNull(lm.path("arch")),
                params > 0 ? parameters(params) : null, textOrNull(lm.path("quantization")), embedding,
                state.equals("loaded") ? Boolean.TRUE : state.equals("not-loaded") ? Boolean.FALSE : null,
                Gpu.UNKNOWN.state(), null, null, Gpu.UNKNOWN.text(), context, dimensions));
        }
        return new FoundServer(c.label(), software, c.root(), c.root() + "/v1", "found", null, null, local,
            details.isEmpty() ? null : String.join(" ", details), false, models);
    }

    private static Integer positive(JsonNode n) {
        return n != null && n.canConvertToInt() && n.asInt() > 0 ? n.asInt() : null;
    }

    /**
     * Whether the "local" box should start ticked: for a server on loopback, or, in a container,
     * for the container host names and Compose services when they lead to this machine or a
     * private network. Never for other names or public addresses: that is the admin's call.
     */
    private boolean suggestLocal(Candidate c, String host, InetAddress[] addresses) {
        if (!ModelHosts.hostSide(addresses)) {
            return false;
        }
        if (Arrays.stream(addresses).allMatch(InetAddress::isLoopbackAddress)) {
            return true;
        }
        return inContainer && (ModelHosts.CONTAINER_HOST_NAMES.contains(host) || BUNDLED_NAMES.contains(host)
            || c.kind() == Kind.BUNDLED);
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
        boolean hostName = ModelHosts.CONTAINER_HOST_NAMES.contains(host);
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

    /** On GPU (all of it in GPU memory), split (any part on the CPU, at most 99%), or CPU only. */
    static Gpu gpu(long size, long vram) {
        if (vram <= 0) {
            return new Gpu("cpu", 0, "CPU only");
        }
        if (size <= 0 || vram >= size) {
            return new Gpu("gpu", 100, "On GPU · " + bytes(vram) + " in GPU memory");
        }
        int pct = (int) Math.max(1, Math.min(99, Math.floor(vram * 100.0 / size)));
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
        return send(HttpRequest.newBuilder(URI.create(url)).timeout(readTimeout).GET()
            .header("Accept", "application/json").header("User-Agent", "Kilnmark").build());
    }

    private Fetched send(HttpRequest request) throws Unreachable {
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

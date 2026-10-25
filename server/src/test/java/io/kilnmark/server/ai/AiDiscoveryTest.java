package io.kilnmark.server.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.ai.AiViews.FoundModel;
import io.kilnmark.server.ai.AiViews.FoundServer;
import io.kilnmark.server.ai.ModelDiscovery.Candidate;
import io.kilnmark.server.ai.ModelDiscovery.Kind;

/**
 * Finding model servers, without a container or DNS: fake Ollama and OpenAI-compatible servers on
 * the JDK's HTTP server, a fake name service, and the plain-words diagnosis of what can go wrong.
 */
class AiDiscoveryTest {

    private final List<HttpServer> servers = new CopyOnWriteArrayList<>();
    private final List<ModelDiscovery> discoveries = new CopyOnWriteArrayList<>();
    /** Names the fake name service was asked for. */
    private final List<String> lookups = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        servers.forEach(s -> s.stop(0));
        discoveries.forEach(ModelDiscovery::close);
    }

    private static KilnmarkConfig config(String... pairs) {
        io.smallrye.config.SmallRyeConfigBuilder b = new io.smallrye.config.SmallRyeConfigBuilder()
            .addDefaultInterceptors().withMapping(KilnmarkConfig.class)
            .withConverter(Duration.class, 200, new io.quarkus.runtime.configuration.DurationConverter())
            .withDefaultValue("kilnmark.secret-key-file", "/tmp/unused.key");
        for (int i = 0; i < pairs.length; i += 2) {
            b.withDefaultValue(pairs[i], pairs[i + 1]);
        }
        return b.build().getConfigMapping(KilnmarkConfig.class);
    }

    /** A fake name service: listed names only ("stall" waits five seconds); everything else is unknown. */
    private ModelHosts hosts(boolean inContainer, Map<String, String> names, String... pairs) {
        return new ModelHosts(config(pairs), host -> {
            lookups.add(host);
            String to = names.get(host);
            if ("stall".equals(to)) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new UnknownHostException(host);
            }
            if (to == null) {
                throw new UnknownHostException(host);
            }
            return new InetAddress[] {InetAddress.getByName(to)};
        }, inContainer);
    }

    private ModelDiscovery discovery(ModelHosts hosts) {
        return discovery(hosts, Duration.ofSeconds(5));
    }

    private ModelDiscovery discovery(ModelHosts hosts, Duration overall) {
        ModelDiscovery d = new ModelDiscovery(hosts, Duration.ofMillis(500), Duration.ofMillis(400), overall);
        discoveries.add(d);
        return d;
    }

    private ModelDiscovery discovery(boolean inContainer, String... pairs) {
        return discovery(hosts(inContainer, Map.of("localhost", "127.0.0.1"), pairs));
    }

    /** How a fake server answers one path. */
    record Reply(int status, String body, long delayMs, boolean chunked, Map<String, String> headers) {
        static Reply json(String body) {
            return new Reply(200, body, 0, false, Map.of());
        }

        static Reply slow(String body, long ms) {
            return new Reply(200, body, ms, false, Map.of());
        }
    }

    /** A request as the fake server saw it. */
    record Seen(String method, String path) {
    }

    private String serve(Map<String, Reply> routes, List<Seen> seen) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        s.createContext("/", x -> {
            String path = x.getRequestURI().getPath();
            seen.add(new Seen(x.getRequestMethod(), path));
            Reply r = routes.getOrDefault(path, new Reply(404, "<html>not here</html>", 0, false,
                Map.of("Content-Type", "text/html")));
            if (r.delayMs() > 0) {
                try {
                    Thread.sleep(r.delayMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] b = r.body().getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type", "application/json");
            r.headers().forEach((k, v) -> x.getResponseHeaders().set(k, v));
            try {
                x.sendResponseHeaders(r.status(), r.chunked() ? 0 : b.length == 0 ? -1 : b.length);
                try (OutputStream out = x.getResponseBody()) {
                    out.write(b);
                }
            } catch (IOException e) {
                // the client gave up
            }
        });
        s.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        s.start();
        servers.add(s);
        return "http://127.0.0.1:" + s.getAddress().getPort();
    }

    private String serve(Map<String, Reply> routes) throws IOException {
        return serve(routes, new CopyOnWriteArrayList<>());
    }

    private static final String TAGS = """
        {"models": [
          {"name": "qwen2.5:7b", "model": "qwen2.5:7b", "size": 4683087332,
           "details": {"family": "qwen2", "families": ["qwen2"], "parameter_size": "7.6B", "quantization_level": "Q4_K_M"}},
          {"name": "llama3.1:70b", "size": 42520413916,
           "details": {"family": "llama", "parameter_size": "70.6B", "quantization_level": "Q4_0"}},
          {"name": "phi3:mini", "size": 2176178913,
           "details": {"family": "phi3", "parameter_size": "3.8B", "quantization_level": "Q4_0"}},
          {"name": "nomic-embed-text:latest", "size": 274302450,
           "details": {"family": "nomic-bert", "families": ["nomic-bert"], "parameter_size": "137M", "quantization_level": "F16"}},
          {"name": "bge-m3:latest", "size": 1157672605, "details": {"family": "bert", "parameter_size": "566.70M"}}
        ]}""";

    private static final String PS = """
        {"models": [
          {"name": "qwen2.5:7b", "model": "qwen2.5:7b", "size": 5100000000, "size_vram": 5100000000, "context_length": 4096},
          {"name": "llama3.1:70b", "model": "llama3.1:70b", "size": 50000000000, "size_vram": 30000000000},
          {"name": "phi3:mini", "model": "phi3:mini", "size": 3000000000, "size_vram": 0}
        ]}""";

    private static final String SHOW = """
        {"model_info": {"general.architecture": "nomic-bert", "nomic-bert.embedding_length": 768,
                        "nomic-bert.context_length": 2048},
         "parameters": "stop <|endoftext|>\\nnum_ctx                        8192"}""";

    private static Map<String, Reply> ollama(String tags, String ps) {
        Map<String, Reply> m = new HashMap<>(Map.of("/api/version", Reply.json("{\"version\": \"0.12.6\"}"),
            "/api/tags", Reply.json(tags), "/api/show", Reply.json(SHOW)));
        if (ps != null) {
            m.put("/api/ps", Reply.json(ps));
        }
        return m;
    }

    private static FoundModel model(FoundServer s, String name) {
        return s.models().stream().filter(m -> m.name().equals(name)).findFirst()
            .orElseThrow(() -> new AssertionError("no model " + name + " in " + s.models()));
    }

    @Test
    void ollamaSaysHowEachLoadedModelUsesTheGpu() throws IOException {
        List<Seen> seen = new CopyOnWriteArrayList<>();
        String root = serve(ollama(TAGS, PS), seen);
        FoundServer s = discovery(false).probe(new Candidate("Ollama", Kind.OLLAMA, root, true)).server();
        assertEquals("found", s.outcome(), String.valueOf(s.problem()));
        assertEquals("Ollama 0.12.6", s.server());
        assertEquals(root + "/v1", s.baseUrl());
        assertTrue(s.gpuReported());
        assertTrue(s.suggestLocal(), "loopback");

        FoundModel onGpu = model(s, "qwen2.5:7b");
        assertEquals("gpu", onGpu.gpu());
        assertEquals("On GPU · 5.1 GB in GPU memory", onGpu.gpuText());
        assertEquals(4096, onGpu.contextSize());
        assertEquals("Q4_K_M", onGpu.quantisation());
        assertEquals("7.6B", onGpu.parameterSize());
        assertEquals(4683087332L, onGpu.sizeBytes());
        assertFalse(onGpu.embedding());
        assertNull(onGpu.dimensions());

        FoundModel split = model(s, "llama3.1:70b");
        assertEquals("split", split.gpu());
        assertEquals(60, split.gpuPercent());
        assertEquals("Split · 60% on GPU", split.gpuText());

        assertEquals("CPU only", model(s, "phi3:mini").gpuText());

        FoundModel idle = model(s, "nomic-embed-text:latest");
        assertEquals("not-loaded", idle.gpu());
        assertEquals(Boolean.FALSE, idle.loaded());
        assertTrue(idle.gpuText().startsWith("Not loaded yet"));
        assertTrue(idle.embedding(), "nomic-embed-text is an embedding model");
        assertEquals(768, idle.dimensions(), "from /api/show");
        assertEquals(8192, idle.contextSize(), "num_ctx, not the trained context_length");
        assertTrue(model(s, "bge-m3:latest").embedding(), "BERT family: embeddings");

        // Only the model server's own paths; only GET, except Ollama's /api/show for embedding models.
        for (Seen r : seen) {
            assertTrue(List.of("/api/version", "/api/tags", "/api/ps", "/api/show").contains(r.path()), r.toString());
            assertEquals(r.path().equals("/api/show") ? "POST" : "GET", r.method(), r.toString());
        }
        assertEquals(2, seen.stream().filter(r -> r.path().equals("/api/show")).count(), "embedding models only");
    }

    @Test
    void withoutApiPsOllamaSaysItDoesNotKnowWhatIsLoaded() throws IOException {
        String root = serve(ollama(TAGS, null));
        FoundServer s = discovery(false).probe(new Candidate("Ollama", Kind.OLLAMA, root, true)).server();
        assertEquals("found", s.outcome());
        assertFalse(s.gpuReported());
        assertTrue(s.detail().contains("/api/ps"), s.detail());
        FoundModel m = model(s, "qwen2.5:7b");
        assertNull(m.loaded());
        assertEquals("unknown", m.gpu());
    }

    @Test
    void gpuUseIsClassifiedFromSizeAndSizeVram() {
        assertEquals("gpu", ModelDiscovery.gpu(1000, 1000).state());
        ModelDiscovery.Gpu nearly = ModelDiscovery.gpu(1000, 990);
        assertEquals("split", nearly.state(), "any part on the CPU is a split");
        assertEquals(99, nearly.percent());
        assertEquals(99, ModelDiscovery.gpu(1000, 999).percent(), "never shown as 100% while split");
        assertEquals(60, ModelDiscovery.gpu(1000, 600).percent());
        assertEquals(1, ModelDiscovery.gpu(1000, 1).percent(), "any GPU use shows as at least 1%");
        assertEquals("cpu", ModelDiscovery.gpu(1000, 0).state());
        assertEquals("850 MB", ModelDiscovery.bytes(850_000_000L));
        assertEquals("1.5B", ModelDiscovery.parameters(1_543_714_304L));
        assertEquals("494M", ModelDiscovery.parameters(494_032_768L));
        assertTrue(ModelDiscovery.looksLikeEmbedding("mxbai-embed-large", null, List.of()));
        assertTrue(ModelDiscovery.looksLikeEmbedding("bge-small-en-v1.5", null, List.of()));
        assertTrue(ModelDiscovery.looksLikeEmbedding("intfloat/multilingual-e5-large", null, List.of()));
        assertFalse(ModelDiscovery.looksLikeEmbedding("qwen2.5-1.5b-instruct", "qwen2", List.of("qwen2")));
        assertFalse(ModelDiscovery.looksLikeEmbedding("gemma3:4b", "gemma3", List.of()));
    }

    @Test
    void anOpenAiCompatibleServerListsItsModelsButNotItsGpu() throws IOException {
        List<Seen> seen = new CopyOnWriteArrayList<>();
        String root = serve(Map.of("/v1/models", Reply.json("""
            {"object": "list", "data": [
              {"id": "qwen2.5-1.5b-instruct", "object": "model", "owned_by": "llamacpp",
               "meta": {"n_params": 1543714304, "size": 1117320736, "n_embd": 1536}},
              {"id": "bge-small-en-v1.5", "object": "model", "owned_by": "llamacpp", "meta": {"n_embd": 384}}
            ]}"""), "/health", Reply.json("{\"status\": \"ok\"}"), "/props", Reply.json("""
            {"default_generation_settings": {"n_ctx": 4096}, "model_path": "/models/qwen2.5-1.5b-instruct-q4_k_m.gguf"}
            """)), seen);
        FoundServer s = discovery(true).probe(new Candidate("Bundled", Kind.BUNDLED, root, true)).server();
        assertEquals("found", s.outcome(), String.valueOf(s.problem()));
        assertEquals("llama-server", s.server());
        assertFalse(s.gpuReported());
        assertEquals("Context size 4096 tokens. Model file qwen2.5-1.5b-instruct-q4_k_m.gguf.", s.detail());
        FoundModel m = model(s, "qwen2.5-1.5b-instruct");
        assertEquals("unknown", m.gpu());
        assertEquals("GPU use not reported by this server", m.gpuText());
        assertEquals("1.5B", m.parameterSize());
        assertEquals(1117320736L, m.sizeBytes());
        assertEquals(4096, m.contextSize());
        assertNull(m.dimensions(), "a chat model has no embedding size to set");
        assertEquals(384, model(s, "bge-small-en-v1.5").dimensions());
        assertTrue(seen.stream().noneMatch(r -> r.path().startsWith("/api/")), "not asked Ollama's questions");
        assertTrue(seen.stream().allMatch(r -> r.method().equals("GET")));

        // vLLM says how long a context the model takes.
        String vllm = serve(Map.of("/v1/models", Reply.json(
            "{\"data\": [{\"id\": \"Qwen/Qwen2.5-7B-Instruct\", \"owned_by\": \"vllm\", \"max_model_len\": 32768}]}")));
        FoundServer v = discovery(false).discover(vllm).servers().get(0);
        assertEquals("vLLM", v.server());
        assertEquals(32768, v.models().get(0).contextSize());
    }

    @Test
    void aTypedAddressThatIsNotOllamaIsAskedTheOpenAiQuestion() throws IOException {
        // Something answers /api/version, but not like Ollama: it may still be an OpenAI-compatible server.
        String plain = serve(Map.of("/api/version", Reply.json("{\"status\": \"ok\"}"), "/v1/models", Reply.json("""
            {"data": [{"id": "text-embedding-nomic-embed-text-v1.5"}, {"id": "llama-3.2-3b-instruct"}]}""")));
        AiViews.Discovery d = discovery(false).discover(plain + "/v1/");
        assertEquals(1, d.servers().size());
        FoundServer typed = d.servers().get(0);
        assertEquals("found", typed.outcome(), String.valueOf(typed.problem()));
        assertEquals("OpenAI-compatible server", typed.server());
        assertEquals(plain + "/v1", typed.baseUrl());
        assertTrue(model(typed, "text-embedding-nomic-embed-text-v1.5").embedding());
        assertFalse(model(typed, "llama-3.2-3b-instruct").embedding());
        assertTrue(typed.suggestLocal(), "127.0.0.1 is this machine");
    }

    @Test
    void lmStudioSaysWhichModelsAreForEmbeddings() throws IOException {
        String root = serve(Map.of("/v1/models", Reply.json("{\"data\": [{\"id\": \"qwen3-8b\", \"owned_by\": "
            + "\"organization_owner\"}, {\"id\": \"my-text-model\", \"owned_by\": \"organization_owner\"}]}"),
            "/api/v0/models", Reply.json("""
            {"data": [{"id": "qwen3-8b", "type": "llm", "state": "loaded", "arch": "qwen3", "quantization": "Q4_K_M",
                       "max_context_length": 32768},
                      {"id": "my-text-model", "type": "embeddings", "state": "not-loaded"}]}""")));
        FoundServer s = discovery(true).probe(new Candidate("LM Studio", Kind.LM_STUDIO, root, true)).server();
        assertEquals("LM Studio", s.server());
        assertEquals(Boolean.TRUE, model(s, "qwen3-8b").loaded());
        assertEquals(32768, model(s, "qwen3-8b").contextSize());
        assertTrue(model(s, "my-text-model").embedding(), "LM Studio's type says so, whatever the name");
        assertEquals(Boolean.FALSE, model(s, "my-text-model").loaded());
    }

    private static int closedPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    @Test
    void aRefusedConnectionIsSaidInPlainWords() throws IOException {
        int port = closedPort();
        FoundServer s = discovery(false).probe(new Candidate("Ollama", Kind.OLLAMA, "http://127.0.0.1:" + port, true))
            .server();
        assertEquals("not-found", s.outcome());
        assertTrue(s.problem().contains("connection refused"), s.problem());
        assertTrue(s.guidance().contains("Ollama is not running"), s.guidance());

        // In a container, localhost is the container.
        FoundServer inside = discovery(true).probe(new Candidate("Ollama", Kind.OLLAMA, "http://127.0.0.1:" + port,
            true)).server();
        assertTrue(inside.guidance().contains("localhost is the container itself"), inside.guidance());
        assertTrue(inside.guidance().contains("host.containers.internal"), inside.guidance());
    }

    @Test
    void aServerThatDoesNotAnswerInTimeIsATimeout() throws IOException {
        String root = serve(Map.of("/api/version", Reply.slow("{}", 1500)));
        long start = System.nanoTime();
        FoundServer s = discovery(false).probe(new Candidate("Ollama", Kind.OLLAMA, root, true)).server();
        assertEquals("not-found", s.outcome());
        assertTrue(s.problem().contains("did not answer within 400 ms"), s.problem());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 3000, "the probe gave up quickly");
    }

    @Test
    void somethingThatIsNotAModelServerIsSaidSo() throws IOException {
        String root = serve(Map.of());
        FoundServer ollama = discovery(false).probe(new Candidate("Ollama", Kind.OLLAMA, root, true)).server();
        assertEquals("not-found", ollama.outcome());
        assertTrue(ollama.problem().contains("HTTP 404 at /api/version"), ollama.problem());
        assertTrue(ollama.problem().contains("not Ollama"), ollama.problem());
        FoundServer typed = discovery(false).discover(root).servers().get(0);
        assertTrue(typed.problem().contains("HTTP 404 at /v1/models"), typed.problem());
        assertTrue(typed.models().isEmpty());

        // JSON, but not a list of models: nothing of the body comes back.
        String odd = serve(Map.of("/v1/models", Reply.json("{\"secret\": \"do-not-echo\"}")));
        FoundServer o = discovery(false).discover(odd).servers().get(0);
        assertTrue(o.problem().contains("did not answer like a model server"), o.problem());
        assertFalse(o.toString().contains("do-not-echo"));

        // Not JSON at all, with status 200.
        String html = serve(Map.of("/v1/models", new Reply(200, "<html>hello</html>", 0, false,
            Map.of("Content-Type", "text/html"))));
        assertTrue(discovery(false).discover(html).servers().get(0).problem().contains("did not answer like"));
    }

    @Test
    void answersOverTheSizeLimitAreNotRead() throws IOException {
        String big = "{\"data\": [\"" + "x".repeat(ModelDiscovery.MAX_BYTES) + "\"]}";
        String sized = serve(Map.of("/v1/models", Reply.json(big)));
        assertTrue(discovery(false).discover(sized).servers().get(0).problem().contains("KB"));
        // The same without a length: chunked, so only the cap stops it.
        String chunked = serve(Map.of("/v1/models", new Reply(200, big, 0, true, Map.of())));
        FoundServer c = discovery(false).discover(chunked).servers().get(0);
        assertTrue(c.problem().contains("KB"), c.problem());
    }

    @Test
    void redirectsAreNotFollowedAndKeysAreNotSent() throws IOException {
        List<Seen> seen = new CopyOnWriteArrayList<>();
        String root = serve(Map.of("/v1/models", new Reply(302, "", 0, false, Map.of("Location", "/elsewhere"))),
            seen);
        FoundServer s = discovery(false).discover(root).servers().get(0);
        assertTrue(s.problem().contains("redirect (HTTP 302)"), s.problem());
        assertTrue(seen.stream().noneMatch(r -> r.path().equals("/elsewhere")), "not followed: " + seen);

        String locked = serve(Map.of("/v1/models", new Reply(401, "{\"error\": \"key\"}", 0, false, Map.of())));
        FoundServer k = discovery(false).discover(locked).servers().get(0);
        assertTrue(k.problem().contains("asks for an API key (HTTP 401)"), k.problem());
        assertTrue(k.guidance().contains("sends no API key"), k.guidance());
    }

    @Test
    void metadataAddressesAreNeverCalledHoweverWritten() {
        ModelDiscovery d = discovery(true);
        for (String address : List.of("http://169.254.169.254/latest", "http://[::ffff:169.254.169.254]/",
            "http://[::ffff:a9fe:a9fe]/", "http://169.254.170.2/", "http://[fd00:ec2::254]/", "http://[fe80::1]/",
            "http://metadata.google.internal", "http://169.254.1.2:11434")) {
            FoundServer s = d.discover(address).servers().get(0);
            assertEquals("refused", s.outcome(), address + ": " + s.problem());
            assertTrue(s.problem().contains("metadata"), s.problem());
        }
        // Unusual ways of writing an address, and absolute names, are a 400, not reinterpreted.
        for (String odd : List.of("http://169.254.43518/", "http://0xa9.0xfe.0xa9.0xfe/", "http://2852039166/",
            "http://0251.0376.0251.0376/", "http://metadata.google.internal./")) {
            assertThrows(IllegalArgumentException.class, () -> d.discover(odd), odd);
        }
        // And refused should one reach the address rules some other way.
        ModelHosts h = hosts(true, Map.of());
        for (String host : List.of("169.254.43518", "0xa9.0xfe.0xa9.0xfe", "2852039166", "017.0.0.1",
            "metadata.google.internal.")) {
            assertTrue(h.forbidden(host, new InetAddress[0]).isPresent(), host);
        }
        assertTrue(h.forbidden("http://2852039166/").isPresent());
        assertTrue(h.forbidden("http://[::ffff:a9fe:a9fe]/").isPresent());
    }

    @Test
    void rootlessPodmansHostAddressIsAllowedOnlyForTheContainerHostNames() {
        // Podman 5.3+ with pasta maps host.containers.internal to 169.254.1.2.
        Map<String, String> pasta = Map.of("host.containers.internal", "169.254.1.2", "host.docker.internal",
            "169.254.1.2", "evil.example", "169.254.1.2", "sneaky.example", "169.254.169.254");
        ModelHosts h = hosts(true, pasta, "kilnmark.ai.hosted-models", "forbidden");
        assertTrue(h.forbidden("http://host.containers.internal:11434/v1").isEmpty());
        assertTrue(h.forbidden("http://host.docker.internal:11434/v1").isEmpty());
        assertTrue(h.mayBeLocal("http://host.containers.internal:11434/v1"), "Podman's host is this machine");
        assertTrue(h.forbidden("http://evil.example/v1").isPresent(), "another name for it is refused");
        assertTrue(h.forbidden("http://sneaky.example/v1").isPresent());
        assertTrue(h.forbidden("http://169.254.1.2:11434/v1").isPresent(), "typed as an address it is refused");
        ModelHosts imds = hosts(true, Map.of("host.containers.internal", "169.254.169.254"));
        assertTrue(imds.forbidden("http://host.containers.internal/v1").isPresent(),
            "the container host names get only 169.254.1.2, not the rest of link-local");
        ModelHosts ecs = hosts(true, Map.of("host.docker.internal", "169.254.170.2"));
        assertTrue(ecs.forbidden("http://host.docker.internal/v1").isPresent());
        // Not in a container: no exception at all.
        assertTrue(hosts(false, pasta).forbidden("http://host.containers.internal:11434/v1").isPresent());
    }

    @Test
    void aContainerHostNamesAnswerIsKeptBriefly() {
        ModelHosts h = hosts(true, Map.of("host.docker.internal", "10.0.2.2"), "kilnmark.ai.hosted-models", "forbidden");
        lookups.clear();
        for (int i = 0; i < 5; i++) {
            assertTrue(h.mayBeLocal("http://host.docker.internal:11434/v1"));
        }
        assertEquals(1, lookups.size(), "looked up once, not per source: " + lookups);
    }

    @Test
    void kilnmarkInContainerTakesTheUsualWordsForYesAndNo() {
        for (String yes : List.of("1", "yes", "TRUE", " True ", "on")) {
            assertEquals(Boolean.TRUE, ModelHosts.yesNo(yes), yes);
        }
        for (String no : List.of("0", "No", "false", "off")) {
            assertEquals(Boolean.FALSE, ModelHosts.yesNo(no), no);
        }
        assertNull(ModelHosts.yesNo("maybe"));
        assertNull(ModelHosts.yesNo(null));
    }

    @Test
    void ollamasEmbeddingDetailsHaveATimeBudget() throws IOException {
        String tags = """
            {"models": [{"name": "nomic-embed-text:a", "size": 1}, {"name": "nomic-embed-text:b", "size": 1},
                        {"name": "nomic-embed-text:c", "size": 1}, {"name": "nomic-embed-text:d", "size": 1}]}""";
        Map<String, Reply> routes = ollama(tags, "{\"models\": []}");
        routes.put("/api/show", Reply.slow(SHOW, 300));
        String root = serve(routes);
        long start = System.nanoTime();
        FoundServer s = discovery(false).probe(new Candidate("Ollama", Kind.OLLAMA, root, true)).server();
        assertEquals("found", s.outcome(), "the slow details do not lose the server: " + s.problem());
        assertEquals(4, s.models().size());
        assertTrue(s.models().stream().anyMatch(m -> m.dimensions() == null), "the budget ran out");
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 2500);
        assertEquals(8192, ModelDiscovery.numCtx("stop x\nnum_ctx 8192"));
        assertNull(ModelDiscovery.numCtx("stop x"));
    }

    @Test
    void containerHostNamesAreLocalOnlyInAContainerAndOnlyWhereTheyLead() {
        String[] lock = {"kilnmark.ai.hosted-models", "forbidden"};
        String url = "http://host.docker.internal:11434/v1";
        assertFalse(hosts(false, Map.of("host.docker.internal", "10.0.2.2"), lock).mayBeLocal(url),
            "outside a container the names are not on the default list");
        assertTrue(hosts(true, Map.of("host.docker.internal", "10.0.2.2"), lock).mayBeLocal(url));
        assertTrue(hosts(true, Map.of("host.docker.internal", "192.168.65.254"), lock).mayBeLocal(url));
        assertFalse(hosts(true, Map.of("host.docker.internal", "203.0.113.9"), lock).mayBeLocal(url),
            "a name service pointing it at a public address does not make that local");
        // Unknown: not refused (which would delete embeddings); the call fails as unreachable instead.
        assertTrue(hosts(true, Map.of(), lock).mayBeLocal(url));
        assertFalse(hosts(true, Map.of(), lock).mayBeLocal("http://host.docker.internal.example.com/v1"));
        // Without the lock the admin's word stands, and nothing is looked up.
        lookups.clear();
        assertTrue(hosts(false, Map.of()).mayBeLocal("http://203.0.113.9/v1"));
        assertTrue(lookups.isEmpty());
    }

    @Test
    void underTheLockHostsNotOnTheListAreNeitherLookedUpNorCalled() throws IOException {
        List<Seen> seen = new CopyOnWriteArrayList<>();
        String root = serve(ollama(TAGS, PS), seen);
        ModelDiscovery narrow = discovery(hosts(true, Map.of(), "kilnmark.ai.hosted-models", "forbidden",
            "kilnmark.ai.local-hosts", "10.0.0.0/8"));
        FoundServer s = narrow.probe(new Candidate("Ollama", Kind.OLLAMA, root, true)).server();
        assertEquals("not-local", s.outcome());
        assertTrue(s.guidance().contains("add 127.0.0.1 to kilnmark.ai.local-hosts"), s.guidance());
        assertTrue(s.guidance().contains("Only do this if it runs on your own network"), s.guidance());
        assertTrue(seen.isEmpty(), "not called: " + seen);
        lookups.clear();
        FoundServer ai = narrow.probe(new Candidate("Bundled", Kind.BUNDLED, "http://ai:8080", true)).server();
        assertEquals("not-local", ai.outcome());
        assertTrue(lookups.isEmpty(), "not looked up: " + lookups);
        assertTrue(narrow.discover(List.of()).locked());

        // The default list in a container: loopback is fine, a container host name leading elsewhere is not.
        ModelDiscovery locked = discovery(hosts(true, Map.of("host.docker.internal", "203.0.113.9"),
            "kilnmark.ai.hosted-models", "forbidden"));
        assertEquals("found", locked.probe(new Candidate("Ollama", Kind.OLLAMA, root, true)).server().outcome());
        FoundServer away = locked.probe(new Candidate("Ollama", Kind.OLLAMA, "http://host.docker.internal:11434", true))
            .server();
        assertEquals("not-local", away.outcome());
        assertTrue(away.problem().contains("203.0.113.9"), away.problem());
    }

    @Test
    void outsideAContainerOnlyLocalhostIsTried() {
        List<String> outside = discovery(false).usual().stream().map(Candidate::root).toList();
        assertEquals(List.of("http://localhost:11434", "http://localhost:1234"), outside);
        List<String> inside = discovery(true).usual().stream().map(Candidate::root).toList();
        assertTrue(inside.containsAll(List.of("http://host.containers.internal:11434", "http://host.docker.internal:11434",
            "http://localhost:11434", "http://host.containers.internal:1234", "http://host.docker.internal:1234",
            "http://localhost:1234", "http://ai:8080", "http://ai-cuda:8080", "http://ai-embed:8080")), inside.toString());
    }

    @Test
    void theUsualPlacesAreTriedTogetherAndOneServerIsListedOnce() throws IOException {
        String slow = serve(Map.of("/api/version", Reply.slow("{}", 1500)));
        String ollama = serve(Map.of("/api/version", Reply.json("{\"version\": \"0.12.6\"}"), "/api/tags",
            Reply.json("{\"models\": []}"), "/api/ps", Reply.json("{\"models\": []}")));
        String mapped = ollama.replace("127.0.0.1", "[::ffff:127.0.0.1]"); // the same server by another name
        long start = System.nanoTime();
        AiViews.Discovery d = discovery(hosts(true, Map.of())).discover(List.of(
            new Candidate("slow", Kind.OLLAMA, slow, true), new Candidate("ok", Kind.OLLAMA, ollama, true),
            new Candidate("again", Kind.OLLAMA, mapped, true),
            new Candidate("nowhere", Kind.BUNDLED, "http://ai-embed:8080", true)));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 3000, "tried in parallel");
        assertEquals(List.of("slow", "ok", "nowhere"), d.servers().stream().map(FoundServer::label).toList());
        FoundServer ok = d.servers().get(1);
        assertEquals("found", ok.outcome());
        assertTrue(ok.detail().contains("Also reachable at " + mapped + "/v1"), ok.detail());
        assertTrue(ok.guidance().contains("ollama pull"), "an empty Ollama says how to get a model");
        FoundServer nowhere = d.servers().get(2);
        assertTrue(nowhere.problem().contains("not known"), nowhere.problem());
        assertTrue(nowhere.guidance().contains("--profile ai"), nowhere.guidance());
        assertTrue(d.inContainer());
    }

    @Test
    void theOverallDeadlineHolds() throws IOException {
        // Each answer comes within the read timeout, but all of them together take too long.
        String crawl = serve(Map.of("/api/version", Reply.slow("{\"version\": \"0.12.6\"}", 300), "/api/tags",
            Reply.slow("{\"models\": []}", 300), "/api/ps", Reply.slow("{\"models\": []}", 300)));
        long start = System.nanoTime();
        AiViews.Discovery d = discovery(hosts(false, Map.of()), Duration.ofMillis(500))
            .discover(List.of(new Candidate("crawl", Kind.OLLAMA, crawl, true)));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 2000);
        assertTrue(d.servers().get(0).problem().contains("too slowly"), d.servers().get(0).problem());

        // A name service that stalls is given up on, and the whole answer still comes in time.
        start = System.nanoTime();
        FoundServer stalled = discovery(hosts(true, Map.of("stall.example", "stall")))
            .discover("http://stall.example:11434").servers().get(0);
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 3000);
        assertTrue(stalled.problem().contains("took longer than"), stalled.problem());
    }

    @Test
    void containerHostNamesGetAdvice() {
        ModelDiscovery inside = discovery(true);
        Candidate podman = new Candidate("Ollama", Kind.OLLAMA, "http://host.containers.internal:11434", true);
        String refused = inside.guidance(podman, ModelDiscovery.Why.REFUSED);
        assertTrue(refused.contains("OLLAMA_HOST=0.0.0.0:11434"), refused);
        assertTrue(refused.contains("firewall"), refused);
        assertNotNull(inside.guidance(podman, ModelDiscovery.Why.TIMEOUT));
        assertTrue(inside.guidance(podman, ModelDiscovery.Why.UNKNOWN_HOST).contains("host.docker.internal"));
        assertTrue(inside.guidance(new Candidate("LM Studio", Kind.LM_STUDIO, "http://host.docker.internal:1234", true),
            ModelDiscovery.Why.REFUSED).contains("Serve on Local Network"));
        assertTrue(discovery(false).guidance(podman, ModelDiscovery.Why.UNKNOWN_HOST).contains("Use localhost"));
        assertNull(inside.guidance(podman, ModelDiscovery.Why.OTHER));
    }

    @Test
    void typedAddressesAreNormalisedAndOddOnesRefused() {
        assertEquals("http://host.containers.internal:11434", ModelDiscovery.root("host.containers.internal:11434"));
        assertEquals("http://h:11434", ModelDiscovery.root(" http://H:11434/v1/ "));
        assertEquals("https://h/llm", ModelDiscovery.root("https://h/llm/v1"));
        assertEquals("http://[::1]:8080", ModelDiscovery.root("http://[::1]:8080/v1"));
        for (String bad : List.of("ftp://h/", "http://user:pw@h/", "http://h/v1?x=1", "http://h/v1#x", "http://",
            "http://h/a/../v1", "http://h/./v1", "http://h/%2e%2e/v1", "http://h/a%2Fb/v1", "http://h/a;b/v1",
            "http://h/a%3Fb", "http://h:99999/v1", "http://h:0/v1", "http://h\\x/v1", "http://ai.:8080/v1",
            "http://017.0.0.1:11434/v1", "http://127.1:11434")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ModelDiscovery.root(bad), bad);
            assertTrue(e.getMessage().startsWith("baseUrl:"), e.getMessage());
        }
    }

    @Test
    void unusualIpv4FormsAndTrailingDotsAreRefusedNotReinterpreted() {
        for (String odd : List.of("0177.0.0.1", "017.0.0.1", "0x7f.0.0.1", "2130706433", "127.1", "256.1.1.1",
            "1.2.3.4.5", "10.0.0.01")) {
            assertTrue(ModelHosts.unusualIpv4(odd), odd);
        }
        for (String fine : List.of("127.0.0.1", "10.0.0.1", "0.0.0.0", "255.255.255.255", "ai-embed", "host.docker.internal")) {
            assertFalse(ModelHosts.unusualIpv4(fine), fine);
        }
        assertTrue(ModelHosts.addressProblem("http://017.0.0.1:11434/v1").isPresent());
        assertTrue(ModelHosts.addressProblem("http://ai.:8080/v1").orElseThrow().contains("dot at the end"));
        assertTrue(ModelHosts.addressProblem("http://ai:8080/v1").isEmpty());
        assertTrue(ModelHosts.addressProblem("http://[::1]:8080/v1").isEmpty());
        // Under the lock an absolute name does not match the listed name.
        ModelHosts narrow = hosts(true, Map.of(), "kilnmark.ai.hosted-models", "forbidden", "kilnmark.ai.local-hosts", "ai");
        assertTrue(narrow.mayBeLocal("http://ai:8080/v1"));
        assertFalse(narrow.mayBeLocal("http://ai.:8080/v1"));
    }
}

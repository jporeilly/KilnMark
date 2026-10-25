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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
 * Finding model servers, without a container: fake Ollama and OpenAI-compatible servers on the
 * JDK's HTTP server, and the plain-words diagnosis of everything that can go wrong.
 */
class AiDiscoveryTest {

    private final List<HttpServer> servers = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        servers.forEach(s -> s.stop(0));
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

    private static ModelDiscovery discovery(boolean inContainer, String... pairs) {
        return new ModelDiscovery(new ModelHosts(config(pairs)), inContainer, Duration.ofMillis(500),
            Duration.ofMillis(400));
    }

    /** A fake server answering {@code routes} (path to JSON body; a body of "!slow" waits past the timeout). */
    private String serve(Map<String, String> routes, List<String> seen) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        s.createContext("/", x -> {
            String path = x.getRequestURI().getPath();
            seen.add(path);
            String body = routes.get(path);
            if ("!slow".equals(body)) {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            int status = body == null ? 404 : 200;
            byte[] b = (body == null ? "<html>not here</html>" : body).getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type", body == null ? "text/html" : "application/json");
            x.sendResponseHeaders(status, b.length);
            try (OutputStream out = x.getResponseBody()) {
                out.write(b);
            } catch (IOException e) {
                // the client gave up
            }
        });
        s.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        s.start();
        servers.add(s);
        return "http://127.0.0.1:" + s.getAddress().getPort();
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

    private static FoundModel model(FoundServer s, String name) {
        return s.models().stream().filter(m -> m.name().equals(name)).findFirst()
            .orElseThrow(() -> new AssertionError("no model " + name + " in " + s.models()));
    }

    @Test
    void ollamaSaysHowEachLoadedModelUsesTheGpu() throws IOException {
        List<String> seen = new CopyOnWriteArrayList<>();
        String root = serve(Map.of("/api/version", "{\"version\": \"0.12.6\"}", "/api/tags", TAGS, "/api/ps", PS), seen);
        FoundServer s = discovery(true).probe(new Candidate("Ollama", Kind.OLLAMA, root, true));
        assertEquals("found", s.outcome(), String.valueOf(s.problem()));
        assertEquals("Ollama 0.12.6", s.server());
        assertEquals(root + "/v1", s.baseUrl());
        assertTrue(s.gpuReported());
        assertTrue(s.suggestLocal());

        FoundModel onGpu = model(s, "qwen2.5:7b");
        assertEquals("gpu", onGpu.gpu());
        assertEquals("On GPU · 5.1 GB in GPU memory", onGpu.gpuText());
        assertEquals(4096, onGpu.contextSize());
        assertEquals("Q4_K_M", onGpu.quantisation());
        assertEquals("7.6B", onGpu.parameterSize());
        assertEquals(4683087332L, onGpu.sizeBytes());
        assertFalse(onGpu.embedding());

        FoundModel split = model(s, "llama3.1:70b");
        assertEquals("split", split.gpu());
        assertEquals(60, split.gpuPercent());
        assertEquals("Split · 60% on GPU", split.gpuText());

        FoundModel cpu = model(s, "phi3:mini");
        assertEquals("cpu", cpu.gpu());
        assertEquals("CPU only", cpu.gpuText());

        FoundModel idle = model(s, "nomic-embed-text:latest");
        assertEquals("not-loaded", idle.gpu());
        assertEquals(Boolean.FALSE, idle.loaded());
        assertTrue(idle.gpuText().startsWith("Not loaded yet"));
        assertTrue(idle.embedding(), "nomic-embed-text is an embedding model");
        assertTrue(model(s, "bge-m3:latest").embedding(), "BERT family: embeddings");

        // Only the model server's own paths, and only GET.
        assertTrue(List.of("/api/version", "/api/tags", "/api/ps").containsAll(seen), seen.toString());
    }

    @Test
    void gpuUseIsClassifiedFromSizeAndSizeVram() {
        assertEquals("gpu", ModelDiscovery.gpu(1000, 1000).state());
        assertEquals("gpu", ModelDiscovery.gpu(1000, 990).state(), "a few bytes short is still all on the GPU");
        assertEquals("split", ModelDiscovery.gpu(1000, 600).state());
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
        List<String> seen = new CopyOnWriteArrayList<>();
        String root = serve(Map.of("/v1/models", """
            {"object": "list", "data": [
              {"id": "qwen2.5-1.5b-instruct", "object": "model", "owned_by": "llamacpp",
               "meta": {"n_params": 1543714304, "size": 1117320736}}
            ]}""", "/health", "{\"status\": \"ok\"}", "/props", """
            {"default_generation_settings": {"n_ctx": 4096}, "model_path": "/models/qwen2.5-1.5b-instruct-q4_k_m.gguf"}
            """), seen);
        FoundServer s = discovery(true).probe(new Candidate("Bundled", Kind.BUNDLED, root, true));
        assertEquals("found", s.outcome(), String.valueOf(s.problem()));
        assertEquals("llama-server", s.server());
        assertFalse(s.gpuReported());
        assertEquals("Context size 4096 tokens. Model file qwen2.5-1.5b-instruct-q4_k_m.gguf.", s.detail());
        FoundModel m = s.models().get(0);
        assertEquals("unknown", m.gpu());
        assertEquals("GPU use not reported by this server", m.gpuText());
        assertEquals("1.5B", m.parameterSize());
        assertEquals(1117320736L, m.sizeBytes());
        assertFalse(seen.contains("/api/version"), "a bundled server is not asked Ollama's questions");

        // A typed address: Ollama's question first, then the OpenAI one. With or without /v1.
        String plain = serve(Map.of("/v1/models", """
            {"data": [{"id": "text-embedding-nomic-embed-text-v1.5"}, {"id": "llama-3.2-3b-instruct"}]}"""),
            new CopyOnWriteArrayList<>());
        AiViews.Discovery d = discovery(false).discover(plain + "/v1/");
        assertEquals(1, d.servers().size());
        FoundServer typed = d.servers().get(0);
        assertEquals("found", typed.outcome(), String.valueOf(typed.problem()));
        assertEquals("OpenAI-compatible server", typed.server());
        assertEquals(plain + "/v1", typed.baseUrl());
        assertTrue(model(typed, "text-embedding-nomic-embed-text-v1.5").embedding());
        assertFalse(model(typed, "llama-3.2-3b-instruct").embedding());
        assertTrue(typed.suggestLocal(), "127.0.0.1 is on the default local list");
    }

    @Test
    void lmStudioSaysWhichModelsAreForEmbeddings() throws IOException {
        String root = serve(Map.of("/v1/models", "{\"data\": [{\"id\": \"qwen3-8b\", \"owned_by\": \"organization_owner\"},"
            + " {\"id\": \"my-text-model\", \"owned_by\": \"organization_owner\"}]}", "/api/v0/models", """
            {"data": [{"id": "qwen3-8b", "type": "llm", "state": "loaded", "arch": "qwen3", "quantization": "Q4_K_M",
                       "max_context_length": 32768},
                      {"id": "my-text-model", "type": "embeddings", "state": "not-loaded"}]}"""),
            new CopyOnWriteArrayList<>());
        FoundServer s = discovery(true).probe(new Candidate("LM Studio", Kind.LM_STUDIO, root, true));
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
        FoundServer s = discovery(false).probe(new Candidate("Ollama", Kind.OLLAMA, "http://127.0.0.1:" + port, true));
        assertEquals("not-found", s.outcome());
        assertTrue(s.problem().contains("connection refused"), s.problem());
        assertTrue(s.guidance().contains("Ollama is not running"), s.guidance());

        // In a container, localhost is the container.
        FoundServer inside = discovery(true).probe(new Candidate("Ollama", Kind.OLLAMA, "http://localhost:" + port, true));
        assertTrue(inside.guidance().contains("localhost is the container itself"), inside.guidance());
        assertTrue(inside.guidance().contains("host.containers.internal"), inside.guidance());
    }

    @Test
    void aServerThatDoesNotAnswerInTimeIsATimeout() throws IOException {
        String root = serve(Map.of("/api/version", "!slow"), new CopyOnWriteArrayList<>());
        long start = System.nanoTime();
        FoundServer s = discovery(false).probe(new Candidate("Ollama", Kind.OLLAMA, root, true));
        assertEquals("not-found", s.outcome());
        assertTrue(s.problem().contains("did not answer within 400 ms"), s.problem());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 1400, "the probe gave up quickly");
    }

    @Test
    void somethingThatIsNotAModelServerIsSaidSo() throws IOException {
        String root = serve(Map.of("/", "{\"hello\": \"web page\"}"), new CopyOnWriteArrayList<>());
        FoundServer ollama = discovery(false).probe(new Candidate("Ollama", Kind.OLLAMA, root, true));
        assertEquals("not-found", ollama.outcome());
        assertTrue(ollama.problem().contains("HTTP 404 at /api/version"), ollama.problem());
        assertTrue(ollama.problem().contains("not Ollama"), ollama.problem());
        FoundServer typed = discovery(false).discover(root).servers().get(0);
        assertTrue(typed.problem().contains("HTTP 404 at /v1/models"), typed.problem());
        assertTrue(typed.models().isEmpty());

        // JSON, but not a list of models: nothing of the body comes back.
        String odd = serve(Map.of("/v1/models", "{\"secret\": \"do-not-echo\"}"), new CopyOnWriteArrayList<>());
        FoundServer o = discovery(false).discover(odd).servers().get(0);
        assertTrue(o.problem().contains("did not answer like a model server"), o.problem());
        assertFalse(o.toString().contains("do-not-echo"));

        // Too big to be a model list: not read.
        String big = serve(Map.of("/v1/models", "{\"data\": [\"" + "x".repeat(ModelDiscovery.MAX_BYTES) + "\"]}"),
            new CopyOnWriteArrayList<>());
        FoundServer b = discovery(false).discover(big).servers().get(0);
        assertTrue(b.problem().contains("KB"), b.problem());
    }

    @Test
    void metadataAddressesAreNeverCalled() {
        FoundServer s = discovery(true).discover("http://169.254.169.254/latest").servers().get(0);
        assertEquals("refused", s.outcome());
        assertTrue(s.problem().contains("metadata"), s.problem());
        assertEquals("refused", discovery(true).discover("http://metadata.google.internal").servers().get(0).outcome());
    }

    @Test
    void underTheLockOnlyLocalHostsAreCalled() throws IOException {
        List<String> seen = new CopyOnWriteArrayList<>();
        String root = serve(Map.of("/api/version", "{\"version\": \"0.12.6\"}", "/api/tags", TAGS), seen);
        ModelDiscovery narrow = discovery(true, "kilnmark.ai.hosted-models", "forbidden", "kilnmark.ai.local-hosts",
            "10.0.0.0/8");
        FoundServer s = narrow.probe(new Candidate("Ollama", Kind.OLLAMA, root, true));
        assertEquals("not-local", s.outcome());
        assertTrue(s.guidance().contains("adds 127.0.0.1 to kilnmark.ai.local-hosts"), s.guidance());
        assertTrue(seen.isEmpty(), "not called: " + seen);
        assertTrue(narrow.discover(List.of()).locked());

        // The default list has the container host names, matched as written; Compose service names are not on it.
        ModelDiscovery locked = discovery(true, "kilnmark.ai.hosted-models", "forbidden");
        assertEquals("found", locked.probe(new Candidate("Ollama", Kind.OLLAMA, root, true)).outcome());
        ModelHosts hosts = new ModelHosts(config("kilnmark.ai.hosted-models", "forbidden"));
        assertTrue(hosts.mayBeLocal("http://host.containers.internal:11434/v1"));
        assertTrue(hosts.mayBeLocal("http://host.docker.internal:11434/v1"));
        assertFalse(hosts.mayBeLocal("http://host.docker.internal.example.com:11434/v1"));
        FoundServer ai = locked.probe(new Candidate("Bundled", Kind.BUNDLED, "http://ai:8080", true));
        assertEquals("not-local", ai.outcome());
        assertTrue(ai.guidance().contains("adds ai to kilnmark.ai.local-hosts"), ai.guidance());
    }

    @Test
    void theUsualPlacesAreTriedTogether() throws IOException {
        List<String> hosts = ModelDiscovery.CANDIDATES.stream().map(c -> c.root()).toList();
        assertTrue(hosts.containsAll(List.of("http://host.containers.internal:11434", "http://host.docker.internal:11434",
            "http://localhost:11434", "http://host.containers.internal:1234", "http://host.docker.internal:1234",
            "http://localhost:1234", "http://ai:8080", "http://ai-cuda:8080", "http://ai-embed:8080")), hosts.toString());

        // Several at once, in parallel: one slow server does not hold up the others for long.
        String slow = serve(Map.of("/api/version", "!slow"), new CopyOnWriteArrayList<>());
        String ollama = serve(Map.of("/api/version", "{\"version\": \"0.12.6\"}", "/api/tags", "{\"models\": []}",
            "/api/ps", "{\"models\": []}"), new CopyOnWriteArrayList<>());
        long start = System.nanoTime();
        AiViews.Discovery d = discovery(true).discover(List.of(new Candidate("slow", Kind.OLLAMA, slow, true),
            new Candidate("ok", Kind.OLLAMA, ollama, true),
            new Candidate("nowhere", Kind.BUNDLED, "http://kb-no-such-host.invalid:8080", true)));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 3000, "tried in parallel");
        assertEquals(List.of("slow", "ok", "nowhere"), d.servers().stream().map(FoundServer::label).toList());
        FoundServer ok = d.servers().get(1);
        assertEquals("found", ok.outcome());
        assertTrue(ok.guidance().contains("ollama pull"), "an empty Ollama says how to get a model");
        FoundServer nowhere = d.servers().get(2);
        assertTrue(nowhere.problem().contains("not known"), nowhere.problem());
        assertTrue(nowhere.guidance().contains("--profile ai"), nowhere.guidance());
        assertTrue(d.inContainer());
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
    void typedAddressesAreNormalised() {
        assertEquals("http://host.containers.internal:11434", ModelDiscovery.root("host.containers.internal:11434"));
        assertEquals("http://h:11434", ModelDiscovery.root(" http://H:11434/v1/ "));
        assertEquals("https://h/llm", ModelDiscovery.root("https://h/llm/v1"));
        assertEquals("http://[::1]:8080", ModelDiscovery.root("http://[::1]:8080/v1"));
        assertThrows(IllegalArgumentException.class, () -> ModelDiscovery.root("ftp://h/"));
        assertThrows(IllegalArgumentException.class, () -> ModelDiscovery.root("http://user:pw@h/"));
        assertThrows(IllegalArgumentException.class, () -> ModelDiscovery.root("http://h/v1?x=1"));
        assertThrows(IllegalArgumentException.class, () -> ModelDiscovery.root("http://"));
    }
}

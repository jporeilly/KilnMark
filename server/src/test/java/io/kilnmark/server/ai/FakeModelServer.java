package io.kilnmark.server.ai;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A tiny OpenAI-compatible model server for tests, on the JDK's HTTP server: deterministic chat
 * answers and embeddings, recorded requests, and failures on demand. No real model is needed.
 *
 * <p>Embeddings: words are folded into a few concepts (so "buyers" and "customers" are close,
 * which keyword search cannot see), hashed into the vector and normalised. The model name sets
 * the size: {@code fake-embed-64} returns 64 numbers.
 */
public final class FakeModelServer {

    /** A request as it arrived. */
    public record Seen(String path, Map<String, List<String>> headers, String body) {
        public String header(String name) {
            for (var e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    return e.getValue().get(0);
                }
            }
            return null;
        }
    }

    private static FakeModelServer instance;

    private final HttpServer server;
    private final ObjectMapper json = new ObjectMapper();
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final AtomicInteger failures = new AtomicInteger();
    private volatile int failStatus = 500;
    private volatile long delayMs;
    private volatile Function<String, String> chat = user -> "{\"ok\": true}";

    private FakeModelServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/chat/completions", this::chat);
        server.createContext("/v1/embeddings", this::embeddings);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    /** The one server for the test run. */
    public static synchronized FakeModelServer get() {
        if (instance == null) {
            try {
                instance = new FakeModelServer();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return instance;
    }

    /** Base URL to put in a model profile. */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    /** Answers every chat request with {@code reply(userMessage)}. */
    public void chatReplies(Function<String, String> reply) {
        this.chat = reply;
    }

    /** Every answer waits this long first (0: none). */
    public void delay(long ms) {
        delayMs = ms;
    }

    /** The next {@code times} requests fail with {@code status}. */
    public void failNext(int status, int times) {
        failStatus = status;
        failures.set(times);
    }

    public List<Seen> seen() {
        return new ArrayList<>(seen);
    }

    public void clear() {
        seen.clear();
        failures.set(0);
        delayMs = 0;
        chat = user -> "{\"ok\": true}";
    }

    private boolean fail(HttpExchange x) throws IOException {
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (failures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            send(x, failStatus, "{\"error\": {\"message\": \"injected failure\", \"type\": \"server_error\"}}");
            return true;
        }
        return false;
    }

    private JsonNode read(HttpExchange x) throws IOException {
        try (InputStream in = x.getRequestBody()) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            seen.add(new Seen(x.getRequestURI().getPath(), Map.copyOf(x.getRequestHeaders()), body));
            return json.readTree(body);
        }
    }

    private void chat(HttpExchange x) throws IOException {
        JsonNode req = read(x);
        if (fail(x)) {
            return;
        }
        String user = "";
        for (JsonNode m : req.path("messages")) {
            if (m.path("role").asText().equals("user")) {
                user = m.path("content").isTextual() ? m.path("content").asText()
                    : m.path("content").path(0).path("text").asText();
            }
        }
        ObjectNode r = json.createObjectNode();
        r.put("id", "chatcmpl-fake");
        r.put("object", "chat.completion");
        r.put("created", 1);
        r.put("model", req.path("model").asText());
        ObjectNode choice = r.putArray("choices").addObject();
        choice.put("index", 0);
        choice.put("finish_reason", "stop");
        ObjectNode msg = choice.putObject("message");
        msg.put("role", "assistant");
        msg.put("content", chat.apply(user));
        ObjectNode usage = r.putObject("usage");
        usage.put("prompt_tokens", 11);
        usage.put("completion_tokens", 7);
        usage.put("total_tokens", 18);
        send(x, 200, json.writeValueAsString(r));
    }

    private void embeddings(HttpExchange x) throws IOException {
        JsonNode req = read(x);
        if (fail(x)) {
            return;
        }
        String model = req.path("model").asText();
        int dims = 64;
        if (model.matches(".*-\\d+")) {
            dims = Integer.parseInt(model.substring(model.lastIndexOf('-') + 1));
        }
        List<String> inputs = new ArrayList<>();
        if (req.path("input").isArray()) {
            req.path("input").forEach(n -> inputs.add(n.asText()));
        } else {
            inputs.add(req.path("input").asText());
        }
        ObjectNode r = json.createObjectNode();
        r.put("object", "list");
        r.put("model", model);
        ArrayNode data = r.putArray("data");
        for (int i = 0; i < inputs.size(); i++) {
            ObjectNode d = data.addObject();
            d.put("object", "embedding");
            d.put("index", i);
            ArrayNode v = d.putArray("embedding");
            for (float f : vector(inputs.get(i), dims)) {
                v.add(f);
            }
        }
        ObjectNode usage = r.putObject("usage");
        usage.put("prompt_tokens", 5 * inputs.size());
        usage.put("total_tokens", 5 * inputs.size());
        send(x, 200, json.writeValueAsString(r));
    }

    /** Words that mean the same thing to this fake model. */
    private static final Map<String, String> CONCEPTS = Map.ofEntries(
        Map.entry("customer", "customer"), Map.entry("customers", "customer"), Map.entry("buyer", "customer"),
        Map.entry("buyers", "customer"), Map.entry("client", "customer"), Map.entry("clients", "customer"),
        Map.entry("people", "customer"), Map.entry("buy", "customer"),
        Map.entry("invoice", "billing"), Map.entry("invoices", "billing"), Map.entry("bill", "billing"),
        Map.entry("bills", "billing"), Map.entry("billing", "billing"), Map.entry("payment", "billing"),
        Map.entry("payments", "billing"));

    /** Deterministic: hashed concepts and words, normalised to length 1. */
    public static float[] vector(String text, int dims) {
        float[] v = new float[dims];
        for (String w : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (w.isEmpty()) {
                continue;
            }
            String c = CONCEPTS.get(w);
            if (c != null) {
                v[Math.floorMod(c.hashCode(), dims)] += 3;
            } else {
                v[Math.floorMod(w.hashCode(), dims)] += 0.2f;
            }
        }
        double norm = 0;
        for (float f : v) {
            norm += f * f;
        }
        if (norm == 0) {
            v[0] = 1;
            return v;
        }
        float n = (float) Math.sqrt(norm);
        for (int i = 0; i < dims; i++) {
            v[i] /= n;
        }
        return v;
    }

    private static void send(HttpExchange x, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(status, b.length);
        try (OutputStream out = x.getResponseBody()) {
            out.write(b);
        }
    }
}

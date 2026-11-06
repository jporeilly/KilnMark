package io.kilnmark.server.quality;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

/**
 * A webhook receiver for {@link QualityFlowTest}, on a port the system picks, started before
 * Kilnmark so its address can go in the webhook allow-list. A fixed port failed when two builds ran
 * the test at once on runners that share one network ("Address already in use").
 */
public class WebhookServerResource implements QuarkusTestResourceLifecycleManager {

    /** What each call to the receiver sent, in order. */
    static final List<String> RECEIVED = new CopyOnWriteArrayList<>();
    private static volatile String base;
    private HttpServer server;

    /** The receiver's address for a rule's webhook, such as {@code http://127.0.0.1:51234/hooks/quality}. */
    static String url(String path) {
        return base + path;
    }

    @SuppressWarnings("AddressSelection") // the loopback address written out, not a host name to look up
    @Override
    public Map<String, String> start() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/hooks/", exchange -> {
            RECEIVED.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/hooks/";
        return Map.of("kilnmark.quality.webhook-allow-list", base);
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }
}

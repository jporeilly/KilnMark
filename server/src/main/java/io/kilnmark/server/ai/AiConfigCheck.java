package io.kilnmark.server.ai;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import io.kilnmark.server.KilnmarkConfig;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Stops the server at start when an AI setting is wrong, rather than at the first model call:
 * every {@code kilnmark.ai.local-hosts} entry must be a network, an IP address or a host name, and
 * {@code kilnmark.ai.usage-retention} must be at least a day. ({@code kilnmark.ai.hosted-models} is
 * an enum, so the configuration itself refuses other values.)
 */
@ApplicationScoped
public class AiConfigCheck {

    private final KilnmarkConfig config;

    public AiConfigCheck(KilnmarkConfig config) {
        this.config = config;
    }

    void onStart(@Observes StartupEvent event) {
        List<String> problems = problems(config);
        if (!problems.isEmpty()) {
            throw new IllegalStateException(String.join(" ", problems));
        }
    }

    /** What is wrong with the AI settings, in words; empty when nothing is. */
    static List<String> problems(KilnmarkConfig config) {
        List<String> out = new ArrayList<>();
        try {
            new ModelHosts(config);
        } catch (IllegalArgumentException e) {
            out.add(e.getMessage() + ".");
        }
        if (config.ai().usageRetention().compareTo(Duration.ofDays(1)) < 0) {
            out.add("kilnmark.ai.usage-retention: at least 1d (one day), for example 90d.");
        }
        return out;
    }
}

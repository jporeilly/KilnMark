package io.kilnmark.server.ai;

import java.time.Instant;
import java.util.List;

/** What the AI endpoints return. API keys never appear here: only whether there is one and its last four characters. */
public final class AiViews {

    private AiViews() {
    }

    public record ProviderInfo(String id, String label, String defaultBaseUrl, boolean needsKey, boolean embeds) {
        public static ProviderInfo of(Provider p) {
            return new ProviderInfo(p.id(), p.label(), p.defaultBaseUrl(), p.needsKey(), p.embeds());
        }
    }

    /** A model profile as listed. {@code baseUrl} is what was entered; {@code effectiveBaseUrl} what is called. */
    public record Profile(String name, String provider, String providerLabel, String baseUrl, String effectiveBaseUrl,
                          String model, String purpose, Integer dimensions, Integer contextSize, boolean local,
                          boolean hasApiKey, String apiKeyHint, int timeoutSeconds, int maxConcurrent,
                          List<String> usedBy, String createdBy, Instant createdAt, Instant updatedAt) {
        public static Profile of(ModelProfile p, List<String> usedBy) {
            return new Profile(p.name(), p.provider().id(), p.provider().label(), p.baseUrl(), p.effectiveBaseUrl(),
                p.model(), p.purpose().id(), p.dimensions(), p.contextSize(), p.local(), p.hasKey(), p.keyHint(),
                p.timeoutSeconds(), p.maxConcurrent(), usedBy, p.createdBy(), p.createdAt(), p.updatedAt());
        }
    }

    /** A feature and the profile it uses ({@code profile} null: off). */
    public record Feature(String id, String label, String help, String purpose, String profile) {
    }

    /** Body of {@code PUT /api/v1/ai/features/{id}}: a profile name, or null to turn the feature off. */
    public record Route(String profile) {
    }

    /**
     * Server-wide rules. {@code hostedModels}: what an admin chose. {@code lockedByServer}: the
     * server's configuration forbids hosted models whatever the admin chooses.
     */
    public record Settings(String hostedModels, boolean lockedByServer, boolean hostedAllowed) {
    }

    /** Body of {@code PUT /api/v1/ai/settings}. */
    public record SettingsChange(String hostedModels) {
    }

    /** The result of testing a profile, in plain words. */
    public record TestResult(boolean ok, String message, long latencyMs, Integer dimensions) {
    }

    public record Usage(int days, List<UsageLog.Total> totals, List<UsageLog.Recent> recent) {
    }

    /** What viewers may know: which AI search features are on. */
    public record Status(boolean searchByMeaning, boolean ask, Embeddings.Status embeddings) {
    }

    /** A search result: {@code mode} is hybrid or keyword, {@code note} why meaning was not used (or null). */
    public record SearchResult(String query, String mode, String note, List<HybridSearch.Hit> hits) {
        public static SearchResult of(String query, HybridSearch.Result r) {
            return new SearchResult(query, r.mode(), r.note(), r.hits());
        }
    }

    /** Body of {@code POST /api/v1/search/ask}. */
    public record Question(String question, Integer limit) {
    }

    /** The interpreted question: {@code plan} null when it was searched as typed. */
    public record AskResult(String question, QueryPlan plan, String interpretedBy, String note, String mode,
                            String searchNote, List<HybridSearch.Hit> hits) {
        public static AskResult of(AskSearch.Answer a) {
            return new AskResult(a.question(), a.plan(), a.interpretedBy(), a.note(), a.result().mode(),
                a.result().note(), a.result().hits());
        }
    }
}

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

    /**
     * A model profile as listed. {@code baseUrl} is what was entered; {@code effectiveBaseUrl} what is
     * called. {@code local} is the admin's tick; {@code effectiveLocal} whether the server treats the
     * model as local now (under the operator lock, only on a local network).
     */
    public record Profile(String name, String provider, String providerLabel, String baseUrl, String effectiveBaseUrl,
                          String model, String purpose, Integer dimensions, Integer contextSize, boolean local,
                          boolean effectiveLocal, boolean hasApiKey, String apiKeyHint, int timeoutSeconds,
                          int maxConcurrent, List<String> usedBy, String createdBy, Instant createdAt,
                          Instant updatedAt) {
        public static Profile of(ModelProfile p, List<String> usedBy, boolean effectiveLocal) {
            return new Profile(p.name(), p.provider().id(), p.provider().label(), p.baseUrl(), p.effectiveBaseUrl(),
                p.model(), p.purpose().id(), p.dimensions(), p.contextSize(), p.local(), effectiveLocal, p.hasKey(),
                p.keyHint(), p.timeoutSeconds(), p.maxConcurrent(), usedBy, p.createdBy(), p.createdAt(),
                p.updatedAt());
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

    /** Body of {@code POST /api/v1/ai/discover}: an address to check, or none for the usual places. */
    public record DiscoverRequest(String baseUrl) {
    }

    /**
     * What {@code POST /api/v1/ai/discover} found. {@code inContainer}: Kilnmark runs in a
     * container, so localhost is the container. {@code locked}: only local hosts were called.
     */
    public record Discovery(List<FoundServer> servers, boolean inContainer, boolean locked) {
    }

    /**
     * One address that was checked. {@code outcome}: {@code found}, {@code not-found} (with
     * {@code problem} in plain words), {@code refused} (a metadata or link-local address, not
     * called) or {@code not-local} (the operator lock, not called). {@code baseUrl} is what to
     * put in a model profile; {@code suggestLocal} whether the "local" box should start ticked;
     * {@code gpuReported} whether the server says how models use the GPU (Ollama does).
     */
    public record FoundServer(String label, String server, String address, String baseUrl, String outcome,
                              String problem, String guidance, boolean suggestLocal, String detail,
                              boolean gpuReported, List<FoundModel> models) {
    }

    /**
     * A model a server offers. Sizes are bytes; {@code loaded} null when the server does not say.
     * {@code gpu}: {@code gpu}, {@code split} (with {@code gpuPercent}), {@code cpu},
     * {@code not-loaded} or {@code unknown}; {@code gpuText} says the same in words.
     * {@code contextSize} and (embedding models) {@code dimensions} when the server says.
     */
    public record FoundModel(String name, Long sizeBytes, String family, String parameterSize, String quantisation,
                             boolean embedding, Boolean loaded, String gpu, Integer gpuPercent, Long vramBytes,
                             String gpuText, Integer contextSize, Integer dimensions) {
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

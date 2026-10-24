package io.kilnmark.server.ai;

import java.util.Locale;

/**
 * Where a model is served. Local servers (llama.cpp's llama-server, vLLM, Ollama, LM Studio) all
 * speak the OpenAI API, so they are one provider: {@link #OPENAI_COMPATIBLE}.
 */
public enum Provider {
    /** Any server with the OpenAI API: llama-server, vLLM, Ollama ({@code /v1}), LM Studio. */
    OPENAI_COMPATIBLE("openai-compatible", "OpenAI-compatible server (llama-server, vLLM, Ollama)", null, false, true),
    OPENAI("openai", "OpenAI", "https://api.openai.com/v1", true, true),
    /** Azure OpenAI's v1 API, which is the OpenAI API at {@code https://RESOURCE.openai.azure.com/openai/v1}. */
    AZURE_OPENAI("azure-openai", "Azure OpenAI", null, true, true),
    ANTHROPIC("anthropic", "Anthropic", "https://api.anthropic.com/v1", true, false),
    GEMINI("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta", true, true);

    private final String id;
    private final String label;
    private final String defaultBaseUrl;
    private final boolean needsKey;
    private final boolean embeds;

    Provider(String id, String label, String defaultBaseUrl, boolean needsKey, boolean embeds) {
        this.id = id;
        this.label = label;
        this.defaultBaseUrl = defaultBaseUrl;
        this.needsKey = needsKey;
        this.embeds = embeds;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    /** The provider's own address, or null when the admin must give one. */
    public String defaultBaseUrl() {
        return defaultBaseUrl;
    }

    public boolean needsKey() {
        return needsKey;
    }

    /** Whether this provider serves embedding models. */
    public boolean embeds() {
        return embeds;
    }

    public static Provider parse(String text) {
        if (text != null) {
            String t = text.trim().toLowerCase(Locale.ROOT);
            for (Provider p : values()) {
                if (p.id.equals(t)) {
                    return p;
                }
            }
        }
        throw new IllegalArgumentException("provider: use openai-compatible, openai, azure-openai, anthropic or gemini");
    }
}

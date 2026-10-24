package io.kilnmark.server.ai;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiEmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import io.kilnmark.server.ai.AiFeature.Purpose;

/**
 * {@link ModelClient} over LangChain4j's provider modules, built from a model profile at run time.
 * LangChain4j makes one attempt (its own retries are off); {@link AiGateway} retries.
 *
 * <p>Answers in JSON: OpenAI-compatible servers (llama-server, vLLM), OpenAI and Azure OpenAI get
 * the JSON schema as {@code response_format}; Gemini is asked for JSON; Anthropic gets the schema
 * in the prompt only. Every answer is parsed and checked by the caller anyway.
 */
final class LangChainModelClient implements ModelClient {

    /** Sent as the key to servers that need none (llama-server without --api-key, Ollama). */
    private static final String NO_KEY = "no-key";

    private final Provider provider;
    private final ChatModel chat;
    private final EmbeddingModel embedder;

    private LangChainModelClient(Provider provider, ChatModel chat, EmbeddingModel embedder) {
        this.provider = provider;
        this.chat = chat;
        this.embedder = embedder;
    }

    /** Builds a client for a profile. {@code apiKey} may be null for servers that need none. */
    static LangChainModelClient create(ModelProfile p, String apiKey) {
        Duration timeout = Duration.ofSeconds(p.timeoutSeconds());
        String url = p.effectiveBaseUrl();
        String key = apiKey == null || apiKey.isBlank() ? NO_KEY : apiKey;
        Map<String, String> headers = p.provider() == Provider.AZURE_OPENAI ? Map.of("api-key", key) : Map.of();
        if (p.purpose() == Purpose.EMBEDDING) {
            EmbeddingModel e = switch (p.provider()) {
                case OPENAI_COMPATIBLE, OPENAI, AZURE_OPENAI -> OpenAiEmbeddingModel.builder().baseUrl(url).apiKey(key)
                    .customHeaders(headers).modelName(p.model()).timeout(timeout).maxRetries(0).build();
                case GEMINI -> GoogleAiEmbeddingModel.builder().baseUrl(url).apiKey(key).modelName(p.model())
                    .timeout(timeout).maxRetries(0).build();
                case ANTHROPIC -> throw new IllegalArgumentException("Anthropic has no embedding models");
            };
            return new LangChainModelClient(p.provider(), null, e);
        }
        ChatModel c = switch (p.provider()) {
            case OPENAI_COMPATIBLE, OPENAI, AZURE_OPENAI -> OpenAiChatModel.builder().baseUrl(url).apiKey(key)
                .customHeaders(headers).modelName(p.model()).timeout(timeout).maxRetries(0)
                .supportedCapabilities(Capability.RESPONSE_FORMAT_JSON_SCHEMA).strictJsonSchema(false).build();
            case ANTHROPIC -> AnthropicChatModel.builder().baseUrl(url).apiKey(key).modelName(p.model())
                .timeout(timeout).maxRetries(0).maxTokens(1024).build();
            case GEMINI -> GoogleAiGeminiChatModel.builder().baseUrl(url).apiKey(key).modelName(p.model())
                .timeout(timeout).maxRetries(0).build();
        };
        return new LangChainModelClient(p.provider(), c, null);
    }

    @Override
    public ChatAnswer chat(ChatCall call) {
        if (chat == null) {
            throw new IllegalStateException("This profile is for embeddings, not chat");
        }
        ChatRequest.Builder b = ChatRequest.builder()
            .messages(SystemMessage.from(call.system()), UserMessage.from(call.user()))
            .maxOutputTokens(call.maxOutputTokens());
        // Hosted OpenAI reasoning models refuse a temperature; everyone else answers more steadily at 0.
        if (provider != Provider.OPENAI && provider != Provider.AZURE_OPENAI) {
            b.temperature(0.0);
        }
        if (call.jsonSchema() != null) {
            switch (provider) {
                case OPENAI_COMPATIBLE, OPENAI, AZURE_OPENAI -> b.responseFormat(ResponseFormat.builder()
                    .type(ResponseFormatType.JSON)
                    .jsonSchema(JsonSchema.builder().name("answer")
                        .rootElement(JsonRawSchema.from(call.jsonSchema())).build())
                    .build());
                case GEMINI -> b.responseFormat(ResponseFormat.JSON);
                case ANTHROPIC -> { } // the schema is in the prompt
            }
        }
        ChatResponse r = chat.chat(b.build());
        TokenUsage u = r.tokenUsage();
        return new ChatAnswer(r.aiMessage() == null ? null : r.aiMessage().text(),
            u == null ? null : u.inputTokenCount(), u == null ? null : u.outputTokenCount());
    }

    @Override
    public EmbedAnswer embed(List<String> texts) {
        if (embedder == null) {
            throw new IllegalStateException("This profile is for chat, not embeddings");
        }
        Response<List<Embedding>> r = embedder.embedAll(texts.stream().map(TextSegment::from).toList());
        TokenUsage u = r.tokenUsage();
        return new EmbedAnswer(r.content().stream().map(Embedding::vector).toList(),
            u == null ? null : u.inputTokenCount());
    }
}

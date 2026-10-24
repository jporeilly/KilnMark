package io.kilnmark.server.ai;

import java.util.List;

/**
 * Kilnmark's own model interface (ADR 0012): chat with an optional JSON schema, and embeddings.
 * Implementations make one attempt; timeouts, retries, concurrency, egress and usage records are
 * {@link AiGateway}'s job, so every provider gets the same treatment.
 */
public interface ModelClient {

    /** One chat turn. {@code jsonSchema}, when given, is the JSON schema the answer must match. */
    record ChatCall(String system, String user, String jsonSchema, int maxOutputTokens) {
    }

    /** Token counts are null when the server does not report them. */
    record ChatAnswer(String text, Integer tokensIn, Integer tokensOut) {
    }

    record EmbedAnswer(List<float[]> vectors, Integer tokensIn) {
    }

    ChatAnswer chat(ChatCall call);

    EmbedAnswer embed(List<String> texts);
}

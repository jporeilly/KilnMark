package io.kilnmark.server.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

/**
 * The shared fake model server never hands one test's slow request to the next test's replies:
 * AssistantTest once recorded AiSearchTest's late "ok?" as its own prompt and failed.
 */
class FakeModelServerTest {

    @Test
    void aSlowRequestLeftByOneTestNeverReachesTheNextTestsReplies() throws Exception {
        FakeModelServer fake = FakeModelServer.get();
        fake.clear();
        fake.delay(800);
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest slow = HttpRequest.newBuilder(URI.create(fake.baseUrl() + "/chat/completions"))
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"model\": \"m\", \"messages\": [{\"role\": \"user\", \"content\": \"ok?\"}]}"))
            .header("Content-Type", "application/json").build();
        CompletableFuture<HttpResponse<String>> late = http.sendAsync(slow, HttpResponse.BodyHandlers.ofString());
        Thread.sleep(200); // the request is in, waiting out its delay

        // The next test begins: it clears the fake and sets replies of its own.
        fake.clear();
        List<String> asked = new CopyOnWriteArrayList<>();
        fake.chatReplies(user -> {
            asked.add(user);
            return "the next test's answer";
        });

        HttpResponse<String> r = late.get();
        assertEquals(200, r.statusCode());
        assertFalse(r.body().contains("the next test's answer"), r.body());
        assertEquals(List.of(), asked, "the late request reached the next test's replies");
        fake.clear();
    }
}

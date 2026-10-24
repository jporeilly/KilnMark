package io.kilnmark.server.ai.eval;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Scores a running Kilnmark's AI search against the golden set in tests/ai-eval/golden.json, with
 * whatever models it is configured to use: search by words alone, hybrid search, and the plans
 * asking in plain English produces. Run with {@code ./gradlew :server:aiEval} (see
 * lab/README.md); not part of the normal build. Results go to {@code dist/build/server/ai-eval}.
 */
@Tag("ai-eval")
class AiEval {

    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String url = System.getProperty("kilnmark.eval.url", "http://localhost:8780").replaceAll("/$", "");
    private final String token = System.getProperty("kilnmark.eval.token", System.getenv("KILNMARK_TOKEN"));
    private final String source = System.getProperty("kilnmark.eval.source", "lab");
    private final Path golden = Path.of(System.getProperty("kilnmark.eval.golden", "../tests/ai-eval/golden.json"));
    private final Path out = Path.of(System.getProperty("kilnmark.eval.out", "build/ai-eval"));
    private final Path baseline = Path.of(System.getProperty("kilnmark.eval.baseline",
        "../tests/ai-eval/baseline.json"));
    private final boolean updateBaseline = Boolean.getBoolean("kilnmark.eval.update-baseline");
    private static final int K = 5;

    private JsonNode get(String path) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(url + path)).GET());
    }

    private JsonNode post(String path, Object body) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(url + path)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))));
    }

    private JsonNode send(HttpRequest.Builder b) throws IOException, InterruptedException {
        b.timeout(Duration.ofMinutes(2)).header("Accept", "application/json");
        if (token != null && !token.isBlank()) {
            b.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            fail("Kilnmark answered " + r.statusCode() + ": " + r.body());
        }
        return json.readTree(r.body());
    }

    private String urn(String path) {
        return "urn:kilnmark:" + source + ":" + path;
    }

    private static List<String> urns(JsonNode hits) {
        List<String> out = new ArrayList<>();
        hits.forEach(h -> out.add(h.path("urn").asText()));
        return out;
    }

    private static List<String> strings(JsonNode n) {
        List<String> out = new ArrayList<>();
        n.forEach(v -> out.add(v.asText()));
        return out;
    }

    @Test
    void scoreSearchAndQuestions() throws Exception {
        if (token == null || token.isBlank()) {
            fail("Set KILNMARK_TOKEN (or -Pkilnmark.eval.token) to a viewer token for " + url);
        }
        JsonNode set = json.readTree(golden.toFile());
        JsonNode status = get("/api/v1/ai/status");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("at", Instant.now().toString());
        report.put("server", url);
        report.put("source", source);
        report.put("status", status);

        Map<String, Object> search = new LinkedHashMap<>();
        for (String mode : List.of("keyword", "hybrid")) {
            List<Double> recall = new ArrayList<>();
            List<Double> rr = new ArrayList<>();
            List<Map<String, Object>> items = new ArrayList<>();
            String usedMode = null;
            for (JsonNode item : set.path("search")) {
                String q = item.path("query").asText();
                List<String> relevant = strings(item.path("relevant")).stream().map(this::urn).toList();
                JsonNode r = get("/api/v1/search?limit=20&mode=" + mode + "&q=" + URLEncoder.encode(q,
                    StandardCharsets.UTF_8));
                usedMode = r.path("mode").asText();
                List<String> ranked = urns(r.path("hits"));
                double rec = EvalMetrics.recallAt(ranked, relevant, K);
                double rank = EvalMetrics.reciprocalRank(ranked, relevant);
                recall.add(rec);
                rr.add(rank);
                items.add(Map.of("query", q, "recallAt" + K, rec, "reciprocalRank", rank,
                    "top", ranked.subList(0, Math.min(K, ranked.size()))));
            }
            search.put(mode, Map.of("modeUsed", String.valueOf(usedMode), "recallAt" + K, EvalMetrics.mean(recall),
                "mrr", EvalMetrics.mean(rr), "items", items));
        }
        report.put("search", search);

        List<Double> planScores = new ArrayList<>();
        List<Map<String, Object>> asks = new ArrayList<>();
        String interpretedBy = null;
        for (JsonNode item : set.path("ask")) {
            String question = item.path("question").asText().replace("{source}", source);
            JsonNode r = post("/api/v1/search/ask", Map.of("question", question, "limit", 20));
            JsonNode plan = r.path("plan");
            double score;
            if (plan.isMissingNode() || plan.isNull()) {
                score = 0;
            } else {
                interpretedBy = r.path("interpretedBy").asText();
                List<Double> parts = new ArrayList<>();
                for (String list : List.of("kinds", "sources", "fields")) {
                    List<String> expected = strings(item.path(list)).stream().map(s -> s.replace("{source}", source))
                        .toList();
                    parts.add(EvalMetrics.sameSet(expected, strings(plan.path(list))));
                }
                if (item.has("text")) {
                    parts.add(plan.path("text").asText().toLowerCase(Locale.ROOT)
                        .contains(item.path("text").asText()) ? 1.0 : 0.0);
                }
                score = EvalMetrics.mean(parts);
            }
            planScores.add(score);
            asks.add(Map.of("question", question, "score", score, "plan", plan.isMissingNode() ? "none" : plan,
                "note", r.path("note").asText("")));
        }
        report.put("ask", Map.of("interpretedBy", String.valueOf(interpretedBy), "planAccuracy",
            EvalMetrics.mean(planScores), "items", asks));

        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("keyword.mrr", (Double) ((Map<?, ?>) search.get("keyword")).get("mrr"));
        scores.put("hybrid.mrr", (Double) ((Map<?, ?>) search.get("hybrid")).get("mrr"));
        scores.put("ask.planAccuracy", EvalMetrics.mean(planScores));
        List<String> worse = compare(scores);
        report.put("scores", scores);
        report.put("regressions", worse);

        Files.createDirectories(out);
        Files.writeString(out.resolve("results.json"), json.writeValueAsString(report));
        Files.writeString(out.resolve("results.md"), markdown(report, search, interpretedBy, planScores)
            + (worse.isEmpty() ? "\nNo score fell below the baseline.\n" : "\nBelow the baseline: " + String.join("; ", worse)
                + ".\n"));
        System.out.println("AI evaluation written to " + out.toAbsolutePath());
        if (updateBaseline) {
            JsonNode old = Files.exists(baseline) ? json.readTree(baseline.toFile()) : json.createObjectNode();
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("about", old.path("about").asText("Scores the next run must not fall below (ADR 0012 rule 5)."));
            b.put("tolerance", old.path("tolerance").asDouble(0.02));
            Map<String, Double> rounded = new LinkedHashMap<>();
            scores.forEach((k, v) -> rounded.put(k, Math.round(v * 1000) / 1000.0));
            b.put("scores", rounded);
            Files.writeString(baseline, json.writeValueAsString(b) + "\n");
            System.out.println("Baseline updated: " + baseline.toAbsolutePath());
            return;
        }
        assertTrue(worse.isEmpty(), "Not as good as the baseline (" + baseline + "): " + String.join("; ", worse)
            + ". Fix the regression, or accept the new scores with -Pkilnmark.eval.update-baseline=true.");
    }

    /**
     * The scores that fell below the baseline by more than its tolerance, in words. A missing
     * baseline file, tolerance or score counts too: without them nothing can be compared, and the
     * release gate must not pass by default.
     */
    private List<String> compare(Map<String, Double> scores) throws IOException {
        List<String> worse = new ArrayList<>();
        if (!Files.exists(baseline)) {
            worse.add("no baseline file at " + baseline);
            return worse;
        }
        JsonNode b = json.readTree(baseline.toFile());
        if (!b.path("tolerance").isNumber()) {
            worse.add("the baseline has no tolerance");
        }
        double tolerance = b.path("tolerance").asDouble(0.02);
        scores.forEach((k, v) -> {
            JsonNode base = b.path("scores").path(k);
            if (!base.isNumber()) {
                worse.add("the baseline has no score for " + k);
            } else if (v < base.asDouble() - tolerance) {
                worse.add(String.format(Locale.ROOT, "%s %.3f (baseline %.3f)", k, v, base.asDouble()));
            }
        });
        return worse;
    }

    @SuppressWarnings("unchecked")
    private String markdown(Map<String, Object> report, Map<String, Object> search, String interpretedBy,
                            List<Double> planScores) {
        StringBuilder b = new StringBuilder("# AI evaluation\n\n");
        b.append("Server ").append(report.get("server")).append(", source `").append(source).append("`, ")
            .append(report.get("at")).append(".\n\n| Search | Mode used | Recall@").append(K).append(" | MRR |\n")
            .append("| --- | --- | --- | --- |\n");
        for (var e : search.entrySet()) {
            Map<String, Object> m = (Map<String, Object>) e.getValue();
            b.append(String.format(Locale.ROOT, "| %s | %s | %.3f | %.3f |%n", e.getKey(), m.get("modeUsed"),
                (Double) m.get("recallAt" + K), (Double) m.get("mrr")));
        }
        b.append(String.format(Locale.ROOT, "%nPlan accuracy (asking in plain English, model %s): %.3f over %d "
            + "questions.%n", interpretedBy, EvalMetrics.mean(planScores), planScores.size()));
        return b.toString();
    }
}

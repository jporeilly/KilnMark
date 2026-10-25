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
 * asking in plain English produces, and the assistant's documentation retrieval and citations
 * (tests/ai-eval/docs.json). Run with {@code ./gradlew :server:aiEval} (see
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
    private final Path docsGolden = Path.of(System.getProperty("kilnmark.eval.docs", "../tests/ai-eval/docs.json"));
    /** Sections the assistant retrieves for a question. */
    private static final int DOCS_K = 6;
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

        Map<String, Object> docs = scoreDocs(status.path("assistant").asBoolean(false));
        report.put("docs", docs);

        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("docs.recallAt6", (Double) docs.get("recallAt" + DOCS_K));
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
     * The assistant's documentation domain over tests/ai-eval/docs.json: retrieval (recall at 6 of
     * the section search, by words and, if the server has an embedding model, meaning) and, when
     * the assistant has a chat model, its answers' citations: valid (each cites a section the
     * search can find) and relevant (at least one cites a section that answers). Questions are
     * paced to stay under the assistant's 20 a minute.
     */
    private Map<String, Object> scoreDocs(boolean answers) throws IOException, InterruptedException {
        JsonNode set = json.readTree(docsGolden.toFile());
        List<Double> recall = new ArrayList<>();
        List<Double> rr = new ArrayList<>();
        List<Double> valid = new ArrayList<>();
        List<Double> relevantCited = new ArrayList<>();
        List<Double> fallbacks = new ArrayList<>();
        List<Map<String, Object>> items = new ArrayList<>();
        String mode = null;
        for (JsonNode item : set.path("items")) {
            String q = item.path("question").asText();
            List<String> relevant = strings(item.path("relevant"));
            String page = item.path("page").asText("");
            JsonNode r = get("/api/v1/assistant/sections?limit=" + DOCS_K + "&q=" + URLEncoder.encode(q,
                StandardCharsets.UTF_8) + (page.isEmpty() ? "" : "&page=" + URLEncoder.encode(page,
                StandardCharsets.UTF_8)));
            mode = r.path("mode").asText();
            List<String> ranked = new ArrayList<>();
            r.path("sections").forEach(h -> ranked.add(h.path("sectionId").asText()));
            double rec = EvalMetrics.recallAt(ranked, relevant, DOCS_K);
            recall.add(rec);
            rr.add(EvalMetrics.reciprocalRank(ranked, relevant));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("question", q);
            m.put("recallAt" + DOCS_K, rec);
            m.put("top", ranked);
            if (answers) {
                Thread.sleep(3_100);
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("question", q);
                if (!page.isEmpty()) {
                    body.put("page", page);
                }
                JsonNode a = post("/api/v1/assistant/ask", body);
                List<String> cited = new ArrayList<>();
                a.path("citations").forEach(c -> cited.add(c.path("sectionId").asText()));
                boolean fallback = a.path("fallback").asBoolean();
                fallbacks.add(fallback ? 1.0 : 0.0);
                if (!fallback) {
                    boolean allValid = a.path("citations").size() > 0;
                    for (JsonNode c : a.path("citations")) {
                        allValid &= c.path("href").asText().startsWith("#/docs/")
                            && a.path("answer").asText().contains("[" + c.path("n").asInt() + "]");
                    }
                    valid.add(allValid ? 1.0 : 0.0);
                    relevantCited.add(cited.stream().anyMatch(relevant::contains) ? 1.0 : 0.0);
                }
                m.put("fallback", fallback);
                m.put("cited", cited);
                m.put("answer", a.path("answer").asText());
            }
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modeUsed", String.valueOf(mode));
        out.put("recallAt" + DOCS_K, EvalMetrics.mean(recall));
        out.put("mrr", EvalMetrics.mean(rr));
        if (answers) {
            // Answers that fell back to a list of sections (model refused, failed or out of context) are
            // not in the citation scores, so their share is reported beside them.
            out.put("fallbackRate", EvalMetrics.mean(fallbacks));
            out.put("answered", valid.size());
            out.put("citationValidity", EvalMetrics.mean(valid));
            out.put("citesRelevant", EvalMetrics.mean(relevantCited));
        }
        out.put("items", items);
        return out;
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
        Map<String, Object> docs = (Map<String, Object>) report.get("docs");
        b.append(String.format(Locale.ROOT, "%nAssistant, documentation (%s): recall@%d %.3f, MRR %.3f.",
            docs.get("modeUsed"), DOCS_K, (Double) docs.get("recallAt" + DOCS_K), (Double) docs.get("mrr")));
        if (docs.containsKey("citationValidity")) {
            b.append(String.format(Locale.ROOT, " Fell back to sections: %.3f. Answers: %s, citations valid %.3f, "
                + "citing a relevant section %.3f.", (Double) docs.get("fallbackRate"), docs.get("answered"),
                (Double) docs.get("citationValidity"), (Double) docs.get("citesRelevant")));
        }
        b.append('\n');
        return b.toString();
    }
}

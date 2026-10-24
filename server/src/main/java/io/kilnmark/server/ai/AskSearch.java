package io.kilnmark.server.ai;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kilnmark.graph.store.GraphStore;
import io.kilnmark.graph.store.Records;
import io.kilnmark.server.Db;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Natural-language search: the {@code search_nl} model turns a question into a {@link QueryPlan},
 * the plan is checked against the catalogue, and the hybrid search runs it. The plan is returned
 * with the results so a person sees what was searched. Without a model, or when it fails, the
 * question is searched as typed, with a note.
 */
@ApplicationScoped
public class AskSearch {

    /** A second try when the first answer is not a usable plan (ADR 0012: retry once, then fall back). */
    private static final int ATTEMPTS = 2;

    private final AiGateway gateway;
    private final HybridSearch search;
    private final EgressPolicy egress;
    private final GraphStore store;
    private final Db db;
    private final ObjectMapper json;

    public AskSearch(AiGateway gateway, HybridSearch search, EgressPolicy egress, GraphStore store, Db db,
                     ObjectMapper json) {
        this.gateway = gateway;
        this.search = search;
        this.egress = egress;
        this.store = store;
        this.db = db;
        this.json = json;
    }

    /** The answer to a question: the plan (null when the question was searched as typed) and the results. */
    public record Answer(String question, QueryPlan plan, String interpretedBy, String note,
                         HybridSearch.Result result) {
    }

    public Answer ask(String question, int limit, String caller) {
        String q = question.strip();
        Optional<ModelProfile> profile = gateway.profileFor(AiFeature.SEARCH_NL);
        if (profile.isEmpty()) {
            return asTyped(q, limit, caller, "Searched as typed: no model is set for asking in plain English. "
                + "An admin can choose one under AI models.");
        }
        ModelProfile p = profile.get();
        List<String> kinds = kinds();
        // Only sources this model may see are named in the prompt (the egress rule, per source).
        List<String> sources = store.sources().stream().filter(s -> egress.refusal(p, s).isEmpty())
            .map(Records.Source::name).toList();
        List<String> allSources = store.sources().stream().map(Records.Source::name).toList();
        ModelClient.ChatCall call = new ModelClient.ChatCall(system(kinds, sources), q, QueryPlan.SCHEMA, 300);
        String failure = null;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try {
                String text = gateway.chatWith(p, AiFeature.SEARCH_NL.id(), null, call, caller, () -> false).answer()
                    .text();
                Optional<JsonNode> raw = QueryPlan.parse(json, text);
                if (raw.isPresent()) {
                    QueryPlan plan = QueryPlan.validate(raw.get(), q, kinds, allSources);
                    HybridSearch.Result r = search.search(plan.text(), plan.filters(), limit, caller, true);
                    return new Answer(q, plan, p.name(), null, r);
                }
                failure = "the model's answer was not a search plan";
            } catch (AiException e) {
                failure = e.getMessage();
                break;
            }
        }
        return asTyped(q, limit, caller, "Searched as typed, because the question could not be interpreted: "
            + failure);
    }

    private Answer asTyped(String q, int limit, String caller, String note) {
        return new Answer(q, null, null, note, search.search(q, HybridSearch.Filters.NONE, limit, caller, true));
    }

    static String system(List<String> kinds, List<String> sources) {
        return "You turn a question about a data catalogue into a search plan. The catalogue holds data assets "
            + "(tables, views, files) with their fields (columns). Answer with one JSON object only, matching this "
            + "JSON schema:\n" + QueryPlan.SCHEMA + "\n"
            + "Known kinds: " + (kinds.isEmpty() ? "none yet" : String.join(", ", kinds)) + ".\n"
            + "Known sources: " + (sources.isEmpty() ? "none you may name" : String.join(", ", sources)) + ".\n"
            + "Use only known kinds and sources; leave a list empty when the question does not ask for it. "
            + "Put field names in fields only when the question asks for assets that have them. "
            + "Keep text short: the subject words of the question.";
    }

    private List<String> kinds() {
        return db.tx(c -> {
            List<String> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT DISTINCT lower(kind) FROM kilnmark.asset WHERE removed_at IS NULL ORDER BY 1 LIMIT 50");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        });
    }
}

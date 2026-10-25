package io.kilnmark.server.ai.mcp;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.mcpjava.server.tools.Tool;
import org.mcpjava.server.tools.ToolArg;
import org.mcpjava.server.tools.ToolResponse;

import io.kilnmark.graph.store.GraphStore;
import io.kilnmark.graph.store.Records;
import io.kilnmark.server.Db;
import io.kilnmark.server.ai.Callers;
import io.kilnmark.server.ai.EgressPolicy;
import io.kilnmark.server.ai.HybridSearch;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The read-only MCP server (ADR 0016): outside AI assistants search and read the catalogue at
 * {@code /mcp} (Streamable HTTP), signed in with an API token (viewer or above; see
 * {@code quarkus.http.auth.permission.mcp} in application.properties).
 *
 * <p>An assistant is a model of unknown location, so by default it sees only sources whose AI
 * rule is {@code allowed}. A token an admin flagged "used by a local assistant"
 * ({@code api_token.local_assistant}) also sees {@code local_only} sources. Sources whose rule is
 * {@code none} are never visible. There is no lineage in the catalogue yet, so there is no lineage
 * tool.
 */
@ApplicationScoped
public class CatalogTools {

    private final HybridSearch search;
    private final GraphStore store;
    private final Db db;
    private final ObjectMapper json;

    @Inject
    SecurityIdentity identity;

    /** Columns marked sensitive: an assistant is a model, so their values are always masked for it. */
    @Inject
    io.kilnmark.server.quality.Sights sights;

    public CatalogTools(HybridSearch search, GraphStore store, Db db, ObjectMapper json) {
        this.search = search;
        this.store = store;
        this.db = db;
        this.json = json;
    }

    @Tool(name = "search_assets", description = "Search the data catalogue for tables, views and files by name, "
        + "description, column names and meaning. Returns each asset's URN, source, kind, location, description "
        + "and row count, best match first. Use get_asset with a URN for its fields.",
        annotations = @Tool.Annotations(title = "Search the catalogue", readOnlyHint = true, destructiveHint = false,
            idempotentHint = true, openWorldHint = false))
    public ToolResponse searchAssets(
        @ToolArg(name = "query", description = "What to look for, for example 'customer email' or 'invoices'") String query,
        @ToolArg(name = "limit", description = "Most results to return, 1 to 50", required = false,
            defaultValue = "10") int limit) {
        if (query == null || query.isBlank()) {
            return ToolResponse.ofError("query: say what to search for");
        }
        if (query.length() > 500) {
            return ToolResponse.ofError("query: 500 characters at most");
        }
        Set<String> hidden = hiddenSources();
        sights.speakingToModel(); // a model never matches withheld documents' text
        int n = Math.max(1, Math.min(limit, 50));
        HybridSearch.Result r = search.search(query.strip(), HybridSearch.Filters.NONE, n + hidden.size() * 5 + 10,
            "mcp: " + caller(), true);
        List<Map<String, Object>> hits = new ArrayList<>();
        for (HybridSearch.Hit h : r.hits()) {
            if (hidden.contains(h.source())) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("urn", h.urn());
            m.put("source", h.source());
            m.put("kind", h.kind());
            m.put("location", h.container());
            m.put("name", h.name());
            m.put("description", h.description());
            m.put("rowCount", h.rowCount());
            m.put("matchingFields", h.matchedFields());
            m.put("matchedBy", h.matchedBy());
            hits.add(m);
            if (hits.size() >= n) {
                break;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query.strip());
        out.put("results", hits);
        if (r.note() != null) {
            out.put("note", r.note());
        }
        return text(out);
    }

    @Tool(name = "get_asset", description = "Read one asset from the data catalogue by its URN: kind, location, "
        + "description, primary key, row count, and each field with its type, description and a profile summary "
        + "(rows, nulls, distinct values).",
        annotations = @Tool.Annotations(title = "Read an asset", readOnlyHint = true, destructiveHint = false,
            idempotentHint = true, openWorldHint = false))
    public ToolResponse getAsset(
        @ToolArg(name = "urn", description = "The asset's URN, as search_assets returns it") String urn) {
        Optional<Records.AssetDetail> found = urn == null ? Optional.empty() : store.asset(urn.strip());
        if (found.isEmpty() || hiddenSources().contains(found.get().source())) {
            return ToolResponse.ofError("No asset " + urn + ". Use search_assets to find URNs.");
        }
        Records.AssetDetail a = found.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("urn", a.urn());
        out.put("source", a.source());
        out.put("kind", a.kind());
        out.put("location", a.container());
        out.put("name", a.name());
        out.put("description", a.description());
        out.put("primaryKey", a.primaryKey());
        out.put("rowCount", a.rowCount());
        out.put("lastScannedAt", a.lastScannedAt() == null ? null : a.lastScannedAt().toString());
        if (a.removedAt() != null) {
            out.put("removedAt", a.removedAt().toString());
        }
        List<Map<String, Object>> fields = new ArrayList<>();
        io.kilnmark.server.quality.Masking.Sight sight = sights.model(a.urn());
        for (Records.FieldDetail f : a.fields()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", f.name());
            m.put("type", f.sourceType());
            m.put("kind", f.kind());
            m.put("nullable", f.nullable());
            m.put("description", f.description());
            m.put("profile", profile(f.profileJson(), f.name(), sight));
            fields.add(m);
        }
        out.put("fields", fields);
        return text(out);
    }

    @Tool(name = "list_sources", description = "List the data sources in the catalogue: name, display name, kind, "
        + "number of assets, and the last scan's status and time.",
        annotations = @Tool.Annotations(title = "List sources", readOnlyHint = true, destructiveHint = false,
            idempotentHint = true, openWorldHint = false))
    public ToolResponse listSources() {
        Set<String> hidden = hiddenSources();
        List<Map<String, Object>> out = db.tx(c -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            // display_name may be a column (added by another migration) or a setting; read whichever exists.
            try (PreparedStatement ps = c.prepareStatement("""
                SELECT s.name, coalesce(to_jsonb(s) ->> 'display_name', s.settings ->> 'display_name', s.name), s.kind,
                       (SELECT count(*) FROM kilnmark.asset a WHERE a.source_id = s.id AND a.removed_at IS NULL),
                       r.status, r.finished_at
                FROM kilnmark.data_source s
                LEFT JOIN LATERAL (SELECT status, finished_at FROM kilnmark.run WHERE source_id = s.id
                                   AND kind = 'scan' ORDER BY id DESC LIMIT 1) r ON true
                ORDER BY s.name""");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (hidden.contains(rs.getString(1))) {
                        continue;
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", rs.getString(1));
                    m.put("displayName", rs.getString(2));
                    m.put("kind", rs.getString(3));
                    m.put("assets", rs.getLong(4));
                    m.put("lastScanStatus", rs.getString(5));
                    m.put("lastScanAt", rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant().toString());
                    rows.add(m);
                }
            }
            return rows;
        });
        return text(Map.of("sources", out));
    }

    /**
     * Names of the sources this caller may not see: {@code none} always, {@code local_only} unless
     * the token is flagged as used by a local assistant. The same rule ({@link EgressPolicy.SourceRule#of})
     * as every other AI path.
     */
    private Set<String> hiddenSources() {
        boolean localAssistant = localAssistant();
        Set<String> out = new HashSet<>();
        for (Records.Source s : store.sources()) {
            EgressPolicy.SourceRule rule = EgressPolicy.SourceRule.of(s);
            if (rule == EgressPolicy.SourceRule.NONE
                || rule == EgressPolicy.SourceRule.LOCAL_ONLY && !localAssistant) {
                out.add(s.name());
            }
        }
        return out;
    }

    /** Whether the calling token is flagged "used by a local assistant" by an admin. */
    private boolean localAssistant() {
        Long id;
        try {
            id = identity.getAttribute(io.kilnmark.server.auth.ApiTokenIdentityProvider.TOKEN_ID);
        } catch (RuntimeException e) {
            return false;
        }
        if (id == null || id == 0) {
            return false;
        }
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT local_assistant FROM kilnmark.api_token WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getBoolean(1);
                }
            }
        });
    }

    /**
     * A field's profile: counts, and value statistics, for sources the caller may see. A column
     * marked sensitive keeps its counts and has its values masked: they never go to an assistant,
     * whoever's token it uses (an assistant cannot ask to unmask).
     */
    private Map<String, Object> profile(String stats, String field, io.kilnmark.server.quality.Masking.Sight sight) {
        if (stats == null) {
            return null;
        }
        try {
            JsonNode n = io.kilnmark.server.quality.Masking.profile(json.readTree(stats), field, sight);
            Map<String, Object> m = new LinkedHashMap<>();
            for (String k : List.of("rows", "nulls", "distinct", "min_length", "max_length")) {
                if (n.has(k)) {
                    m.put(k, n.get(k).asLong());
                }
            }
            for (String k : List.of("min", "max", "median", "mean")) {
                if (n.has(k)) {
                    m.put(k, n.get(k).asText());
                }
            }
            if (n.has("top")) {
                m.put("top", json.convertValue(n.get("top"), Object.class));
            }
            return m;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private ToolResponse text(Object value) {
        try {
            return ToolResponse.ofText(json.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            return ToolResponse.ofError("Could not write the answer: " + e.getOriginalMessage());
        }
    }

    private String caller() {
        try {
            return Callers.of(identity);
        } catch (RuntimeException e) {
            return "unknown";
        }
    }
}

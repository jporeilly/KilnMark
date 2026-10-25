package io.kilnmark.server.ai.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.mcpjava.server.tools.Tool;
import org.mcpjava.server.tools.ToolArg;
import org.mcpjava.server.tools.ToolResponse;

import io.kilnmark.server.ai.assistant.KnowledgeIndex;
import io.kilnmark.server.ai.assistant.KnowledgeRanking;
import io.kilnmark.server.ai.assistant.KnowledgeSearch;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The documentation on the MCP server ({@link CatalogTools} has the catalogue): outside assistants
 * search the guides and changelog that ship with this server, as the in-app assistant does. Words
 * only: no model is called. The documentation holds no customer data, so every caller sees all of it.
 */
@ApplicationScoped
public class DocsTools {

    /** Characters of each section's text returned. */
    static final int TEXT_CHARS = 3000;

    private final KnowledgeSearch search;
    private final ObjectMapper json;

    public DocsTools(KnowledgeSearch search, ObjectMapper json) {
        this.search = search;
        this.json = json;
    }

    @Tool(name = "search_docs", description = "Search Kilnmark's own documentation (user guides and the changelog "
        + "shipped with this server) for how to use Kilnmark. Returns matching sections, best first, each with its id, "
        + "title, page, text and its link in the Kilnmark web app (append href to the server's address).",
        annotations = @Tool.Annotations(title = "Search the documentation", readOnlyHint = true,
            destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ToolResponse searchDocs(
        @ToolArg(name = "query", description = "What to look for, for example 'quality rule thresholds'") String query,
        @ToolArg(name = "limit", description = "Most sections to return, 1 to 20", required = false,
            defaultValue = "6") int limit) {
        if (query == null || query.isBlank()) {
            return ToolResponse.ofError("query: say what to search for");
        }
        if (query.length() > 500) {
            return ToolResponse.ofError("query: 500 characters at most");
        }
        String q = query.strip();
        KnowledgeSearch.Result r = search.search(KnowledgeIndex.DOCS, q, null, Math.max(1, Math.min(limit, 20)),
            "mcp", false);
        List<Map<String, Object>> sections = new ArrayList<>();
        for (KnowledgeSearch.Hit h : r.hits()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", h.sectionId());
            m.put("title", h.titlePath());
            m.put("page", h.page());
            m.put("href", h.href());
            m.put("snippet", KnowledgeRanking.snippet(h.body(), q, 220));
            m.put("text", h.body().length() <= TEXT_CHARS ? h.body() : h.body().substring(0, TEXT_CHARS) + " …");
            sections.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", q);
        out.put("sections", sections);
        try {
            return ToolResponse.ofText(json.writeValueAsString(out));
        } catch (JsonProcessingException e) {
            return ToolResponse.ofError("Could not write the answer: " + e.getOriginalMessage());
        }
    }
}

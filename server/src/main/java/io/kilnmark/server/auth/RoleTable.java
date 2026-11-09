package io.kilnmark.server.auth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What each role may do, as Settings › Access review shows it (ADR 0037): one line for each thing a
 * role allows, with the least role it takes, whether it also takes the sensitive-data grant
 * ({@link AccessRole#UNMASK_GRANT}), and the endpoints that allow it. {@code RoleTableTest} holds it
 * to {@code @RolesAllowed}: every endpoint above viewer must be named here, a line checked only there
 * must name endpoints that ask exactly its role, and a line checked in the code too ({@code inCode})
 * endpoints that ask no more. {@code RoleTableProbeTest} then tries each line as every role: a line
 * checked in the code on every endpoint it names, any other on one. What neither catches is a check
 * in the code that narrows a line not marked {@code inCode}; the lines below say those narrowings in
 * words (run again, for example).
 *
 * <p>Plain words for the people who review access, not a full list of every rule: the guide
 * (docs/guide/signing-in.md) has the finer ones, and each line names only what the server enforces.
 */
public final class RoleTable {

    /**
     * One thing a role allows: an id, what it is in words, the least role, whether it also takes the
     * sensitive-data grant (which only a designer may hold), whether its check is in the code beyond
     * the endpoints' {@code @RolesAllowed}, and the endpoints, as {@code METHOD /path}.
     */
    public record Can(String id, String what, AccessRole least, boolean grant, boolean inCode, List<String> endpoints) {

        /** Whether a caller with {@code role}, holding the grant or not, may do it. */
        public boolean allows(AccessRole role, boolean holdsGrant) {
            return role.atLeast(least) && (!grant || (holdsGrant && role.mayHoldUnmask()));
        }

        /** For each role, by its id: {@code yes}, {@code grant} (only with the sensitive-data grant) or {@code no}. */
        public Map<String, String> byRole() {
            Map<String, String> out = new LinkedHashMap<>();
            for (AccessRole r : AccessRole.values()) {
                out.put(r.id(), allows(r, false) ? "yes" : allows(r, true) ? "grant" : "no");
            }
            return out;
        }
    }

    private static Can can(String id, String what, AccessRole least, String... endpoints) {
        return new Can(id, what, least, false, false, List.of(endpoints));
    }

    private static Can inCode(String id, String what, AccessRole least, boolean grant, String... endpoints) {
        return new Can(id, what, least, grant, true, List.of(endpoints));
    }

    /** Every line, from the least role to the most, as the access review shows them. */
    public static final List<Can> LINES = List.of(
        can("read", "Search and read the catalogue: sources, assets, pipelines, flows, runs, schedules, lineage and "
            + "quality results, without any data values", AccessRole.VIEWER,
            "GET /api/v1/search", "GET /api/v1/sources", "GET /api/v1/pipelines", "GET /api/v1/flows",
            "GET /api/v1/schedules", "GET /api/v1/lineage", "GET /api/v1/quality/issues"),
        can("assistant", "Ask the assistant and search by meaning (a model never sees values marked sensitive)",
            AccessRole.VIEWER, "POST /api/v1/assistant/ask", "POST /api/v1/search/ask"),
        can("data", "See data values: samples, previews and rule previews, with columns marked sensitive masked",
            AccessRole.STEWARD, "GET /api/v1/quality/asset/samples", "POST /api/v1/quality/rules/preview",
            "GET /api/v1/sources/{name}/preview", "GET /api/v1/sources/{name}/explore",
            "GET /api/v1/sources/{name}/explore/count"),
        can("table-sql", "Have Kilnmark write the SQL to make or change one of a source's tables, as text that is "
            + "never run", AccessRole.STEWARD, "POST /api/v1/sources/{name}/explore/sql"),
        can("run", "Run pipelines and flows, cancel runs, and run a failed flow again (one that acts as a role no "
            + "higher than theirs)", AccessRole.STEWARD,
            "POST /api/v1/pipelines/{name}/runs", "POST /api/v1/flows/{name}/runs",
            "POST /api/v1/pipeline-runs/{id}/cancel", "POST /api/v1/flow-runs/{id}/cancel",
            "POST /api/v1/flow-runs/{id}/run-again"),
        can("check-sources", "Test, browse and scan sources", AccessRole.STEWARD,
            "POST /api/v1/sources/{name}/test", "GET /api/v1/sources/{name}/containers",
            "GET /api/v1/sources/{name}/assets", "POST /api/v1/sources/{name}/scans"),
        can("quality", "Look after quality: add, change, run and delete rules (custom SQL ones only as part of "
            + "checking a whole asset), check assets, set baselines, work on issues and add patterns",
            AccessRole.STEWARD,
            "POST /api/v1/quality/rules", "PUT /api/v1/quality/rules/{id}", "DELETE /api/v1/quality/rules/{id}",
            "POST /api/v1/quality/rules/{id}/run", "POST /api/v1/quality/asset/runs",
            "PUT /api/v1/quality/asset/baseline", "PUT /api/v1/quality/issues/{id}",
            "POST /api/v1/quality/asset/patterns", "DELETE /api/v1/quality/asset/patterns/{id}"),
        can("mark", "Mark a column sensitive", AccessRole.STEWARD, "PUT /api/v1/quality/asset/sensitive"),
        can("relationships", "Accept or reject suggested relationships between tables", AccessRole.STEWARD,
            "POST /api/v1/relationships/{id}/accept", "POST /api/v1/relationships/{id}/reject"),
        can("read-variables", "Read server variables", AccessRole.STEWARD, "GET /api/v1/variables",
            "GET /api/v1/variables/{name}", "GET /api/v1/variables/{name}/changes"),
        can("build", "Build pipelines and flows: add, change, preview and delete them, ask the assistant to suggest "
            + "one, and set their owners", AccessRole.DESIGNER,
            "POST /api/v1/pipelines", "PUT /api/v1/pipelines/{name}", "DELETE /api/v1/pipelines/{name}",
            "POST /api/v1/pipelines/preview", "POST /api/v1/pipelines/{name}/versions/{n}/preview",
            "POST /api/v1/pipelines/suggest", "PUT /api/v1/pipelines/{name}/owners",
            "POST /api/v1/flows", "PUT /api/v1/flows/{name}", "DELETE /api/v1/flows/{name}",
            "PUT /api/v1/flows/{name}/owners"),
        can("schedules", "Set, change and delete schedules", AccessRole.DESIGNER, "POST /api/v1/schedules",
            "PUT /api/v1/schedules/{id}", "DELETE /api/v1/schedules/{id}"),
        can("sources", "Add, import, change and delete sources, and turn Allow writing and Allow SQL (PostgreSQL "
            + "only) on or off",
            AccessRole.DESIGNER, "POST /api/v1/sources", "PUT /api/v1/sources/{name}",
            "DELETE /api/v1/sources/{name}", "POST /api/v1/sources/import", "GET /api/v1/imports/{id}",
            "POST /api/v1/drafts/test", "POST /api/v1/drafts/containers", "POST /api/v1/drafts/assets",
            "POST /api/v1/drafts/preview"),
        inCode("set-variables", "Set and delete server variables", AccessRole.DESIGNER, false,
            "PUT /api/v1/variables/{name}", "DELETE /api/v1/variables/{name}"),
        can("samples", "Add the samples, and create the sample tables", AccessRole.DESIGNER, "POST /api/v1/samples",
            "POST /api/v1/samples/tables"),
        inCode("write-sql", "Write SQL into what they build: custom SQL rules, a pipeline read that runs a query, "
            + "a flow's sql node", AccessRole.DESIGNER, true,
            "POST /api/v1/quality/rules", "POST /api/v1/pipelines", "POST /api/v1/flows"),
        inCode("reading-marked", "Change how a source with columns marked sensitive is read (its folders, patterns "
            + "or formats)", AccessRole.DESIGNER, true, "PUT /api/v1/sources/{name}"),
        inCode("unmask", "See values of columns marked sensitive unmasked, never while answering a model",
            AccessRole.DESIGNER, true),
        inCode("clear-mark", "Stop treating a column as sensitive, and delete a source that has columns marked "
            + "sensitive", AccessRole.ADMIN, false, "PUT /api/v1/quality/asset/sensitive",
            "DELETE /api/v1/sources/{name}"),
        inCode("admin-kinds", "Set up generic JDBC sources, whose driver Kilnmark cannot vet", AccessRole.ADMIN, false,
            "POST /api/v1/sources", "POST /api/v1/drafts/test"),
        can("settings", "Settings: AI models, features and usage, the assistant's feedback and tokens, mail and "
            + "Teams, database drivers, the samples on offer and the Starter Pack", AccessRole.ADMIN,
            "GET /api/v1/ai/providers", "GET /api/v1/ai/profiles", "GET /api/v1/ai/profiles/{name}",
            "POST /api/v1/ai/profiles", "PUT /api/v1/ai/profiles/{name}", "DELETE /api/v1/ai/profiles/{name}",
            "POST /api/v1/ai/profiles/{name}/test", "POST /api/v1/ai/discover", "GET /api/v1/ai/features",
            "PUT /api/v1/ai/features/{feature}", "GET /api/v1/ai/settings", "PUT /api/v1/ai/settings",
            "GET /api/v1/ai/usage", "POST /api/v1/ai/embeddings/refresh", "GET /api/v1/assistant/feedback",
            "GET /api/v1/ai/assistant-tokens", "PUT /api/v1/ai/assistant-tokens/{id}",
            "GET /api/v1/mail/settings", "PUT /api/v1/mail/settings", "POST /api/v1/mail/test",
            "GET /api/v1/mail/teams", "POST /api/v1/mail/teams", "PUT /api/v1/mail/teams/{id}",
            "DELETE /api/v1/mail/teams/{id}", "GET /api/v1/mail/log",
            "GET /api/v1/drivers", "POST /api/v1/drivers/{id}/accept", "GET /api/v1/drivers/registered",
            "POST /api/v1/drivers/registered", "DELETE /api/v1/drivers/registered/{name}",
            "GET /api/v1/drivers/registered/history", "GET /api/v1/drivers/files",
            "POST /api/v1/drivers/files/{fileName}", "PUT /api/v1/samples/offered",
            "GET /api/v1/starter-pack/details", "POST /api/v1/starter-pack", "POST /api/v1/starter-pack/people",
            "DELETE /api/v1/starter-pack"),
        can("tokens", "Make and revoke API tokens, and approve or refuse a token's sensitive-data grant",
            AccessRole.ADMIN, "POST /api/v1/tokens", "GET /api/v1/tokens", "GET /api/v1/tokens/policy",
            "DELETE /api/v1/tokens/{id}", "POST /api/v1/tokens/{id}/unmask/approve",
            "POST /api/v1/tokens/{id}/unmask/refuse"),
        can("audit", "Read the audit log and the access review, and record the people the control script made",
            AccessRole.ADMIN, "GET /api/v1/audit", "GET /api/v1/access-review", "POST /api/v1/auth/people"));

    private RoleTable() {
    }
}

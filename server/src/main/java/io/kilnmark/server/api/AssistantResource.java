package io.kilnmark.server.api;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;

import io.kilnmark.server.ai.Callers;
import io.kilnmark.server.ai.assistant.Assistant;
import io.kilnmark.server.ai.assistant.AssistantFeedback;
import io.kilnmark.server.ai.assistant.CallerRate;
import io.kilnmark.server.ai.assistant.KnowledgeIndex;
import io.kilnmark.server.ai.assistant.KnowledgeSearch;
import io.kilnmark.server.auth.AccessRole;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The assistant (docs/design/assistant.md): questions answered from the documentation, with
 * checked citations; a section search for when no chat model is set; and feedback on answers.
 * Any signed-in user, except the feedback list (admins).
 */
@Path("/api/v1/assistant")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AccessRole.VIEWER_ROLE)
public class AssistantResource {

    /** Questions per token per minute (each may call a model), and feedback and searches likewise. */
    static final CallerRate ASKS = new CallerRate(20);
    static final CallerRate OTHERS = new CallerRate(60);

    private final Assistant assistant;
    private final KnowledgeSearch search;
    private final AssistantFeedback feedback;
    private final SecurityIdentity identity;

    public AssistantResource(Assistant assistant, KnowledgeSearch search, AssistantFeedback feedback,
                             SecurityIdentity identity) {
        this.assistant = assistant;
        this.search = search;
        this.feedback = feedback;
        this.identity = identity;
    }

    /** Body of {@code POST /api/v1/assistant/ask}. */
    public record AskRequest(String question, String page, List<Assistant.Turn> history) {
    }

    /** One matching section; {@code score} and {@code matchedBy} say how it was found. */
    public record SectionHit(String sectionId, String page, String title, String href, String snippet, double score,
                             List<String> matchedBy) {
    }

    /** Result of {@code GET /api/v1/assistant/sections}. */
    public record SectionsResult(String query, String page, String mode, String note, List<SectionHit> sections) {
    }

    private void limit(CallerRate rate, String caller, String what) {
        if (!rate.allow(caller, Instant.now())) {
            throw ApiError.of(Response.Status.TOO_MANY_REQUESTS, "You have " + what + " " + rate.perMinute()
                + " times in the last minute: wait a minute, then try again");
        }
    }

    @POST
    @Path("ask")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Ask the assistant", description = "Answers a question from the documentation that ships "
        + "with this server, in a few sentences, citing the sections it used as [n]. Every citation is checked "
        + "against the sections sent to the model and links to the in-app documentation (href, #/docs/<page>/<section>). "
        + "page is the documentation page the person is on (for example quality-rules); its sections rank higher. "
        + "history holds up to 6 earlier turns ({role: user|assistant, text}). Without a chat model for the assistant "
        + "feature, or when the model fails or the server's rule on hosted models refuses it, fallback is true and "
        + "sections lists the best matches. Question 2,000 characters at most, each turn 4,000; 20 questions a "
        + "minute per token. Role: any signed-in user.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The answer with its citations, or the matching sections"),
        @APIResponse(responseCode = "400", description = "Question missing or too long, history too long, or page not "
            + "a page id"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "429", description = "Too many questions in the last minute")})
    public Assistant.Answer ask(AskRequest body) {
        if (body == null) {
            throw new IllegalArgumentException("question: what you want to know, in your own words");
        }
        Assistant.validate(body.question(), body.page(), body.history());
        String caller = Callers.of(identity);
        limit(ASKS, caller, "asked");
        return assistant.ask(body.question(), body.page(), body.history(), caller);
    }

    @GET
    @Path("sections")
    @Operation(summary = "Search the documentation's sections", description = "The sections of the bundled "
        + "documentation that best match q, with a snippet and the in-app link, by words and, when an embedding "
        + "model is set, meaning. page (a documentation page id) ranks that page's sections higher; with no q it "
        + "lists that page's sections. No chat model is called. Role: any signed-in user.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Matching sections, best first"),
        @APIResponse(responseCode = "400", description = "Neither q nor page, q too long, or page not a page id"),
        @APIResponse(responseCode = "401", description = "Not signed in")})
    public SectionsResult sections(@QueryParam("q") String q, @QueryParam("page") String page,
                                   @QueryParam("limit") Integer limit) {
        String hint = Assistant.validPage(page);
        String query = q == null ? "" : q.strip();
        if (query.isEmpty() && hint == null) {
            throw new IllegalArgumentException("q: what to look for in the documentation");
        }
        if (query.length() > Assistant.MAX_QUESTION) {
            throw new IllegalArgumentException("q: 2,000 characters at most");
        }
        String caller = Callers.of(identity);
        limit(OTHERS, caller, "searched the documentation");
        int n = limit == null ? 10 : Math.max(1, Math.min(limit, 50));
        KnowledgeSearch.Result r = search.search(KnowledgeIndex.DOCS, query, hint, n, caller, true);
        return new SectionsResult(query, hint, r.mode(), r.note(), r.hits().stream()
            .map(h -> {
                Assistant.SectionLink l = Assistant.link(h, query);
                return new SectionHit(l.sectionId(), l.page(), l.title(), l.href(), l.snippet(), h.score(),
                    h.matchedBy());
            }).toList());
    }

    @POST
    @Path("feedback")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Rate an answer", description = "A thumbs up (helpful: true) or down on an answer, with the "
        + "question, the answerId and the sectionIds of its sources, and optionally a comment, so admins see where "
        + "the documentation falls short. The answer's own text is stored only while kilnmark.ai.keep-usage-text is "
        + "on. Role: any signed-in user.")
    @APIResponses({
        @APIResponse(responseCode = "201", description = "Recorded"),
        @APIResponse(responseCode = "400", description = "Question or helpful missing, or a field too long"),
        @APIResponse(responseCode = "401", description = "Not signed in")})
    public Response giveFeedback(AssistantFeedback.Input body) {
        String caller = Callers.of(identity);
        limit(OTHERS, caller, "rated answers");
        long id = feedback.record(body, caller);
        return Response.created(URI.create("/api/v1/assistant/feedback/" + id)).entity(Map.of("id", id)).build();
    }

    @GET
    @Path("feedback")
    @RolesAllowed(AccessRole.ADMIN_ROLE)
    @Operation(summary = "Recent feedback on answers (admins only)", description = "Newest first. helpful=false "
        + "lists thumbs down only: questions the documentation did not answer well. Role: admin.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Feedback, newest first"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "403", description = "Not an admin")})
    public List<AssistantFeedback.Entry> listFeedback(@QueryParam("helpful") Boolean helpful,
                                                      @QueryParam("limit") Integer limit) {
        return feedback.recent(limit == null ? 50 : Math.max(1, Math.min(limit, 500)), helpful);
    }
}

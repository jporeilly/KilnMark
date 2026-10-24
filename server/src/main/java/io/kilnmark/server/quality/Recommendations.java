package io.kilnmark.server.quality;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.kilnmark.server.quality.ProfileFacts.Counted;
import io.kilnmark.server.quality.ProfileFacts.FieldFacts;

/**
 * Rules recommended from the profile alone, without AI: what the data already obeys, so that a
 * steward can turn it into a rule that will notice when it stops. Patterns, ranges, small sets of
 * values, uniqueness, completeness, freshness and volume.
 */
public final class Recommendations {

    /** A rule worth adding, ready to post as a {@link RuleDraft}, with why. */
    public record Recommendation(String field, String type, String dimension, String name, ObjectNode params,
                                 String reason) {
    }

    private static final JsonNodeFactory J = JsonNodeFactory.instance;

    private Recommendations() {
    }

    /**
     * Recommendations for {@code now}, leaving out any rule of the same type on the same field
     * already in {@code existing} (as {@code "type:field"}, field empty for the asset).
     */
    public static List<Recommendation> of(ProfileFacts now, List<ProfileFacts> history, Set<String> existing,
                                          Duration defaultCadence) {
        List<Recommendation> out = new ArrayList<>();
        for (FieldFacts f : now.fields()) {
            if (f.stats() == null || f.rows() <= 0) {
                continue;
            }
            long values = f.values();
            if (f.nulls() == 0 && f.get("blanks") <= 0 && f.rows() >= 10) {
                add(out, existing, new Recommendation(f.name(), "not_null", "completeness", f.name() + " is filled",
                    J.objectNode().put("blankIsMissing", f.text()), "Every one of " + Measures.count(f.rows())
                        + " rows has a value"));
            }
            long distinct = f.get("distinct");
            boolean exact = !"false".equals(f.text("distinct_exact"));
            boolean key = now.primaryKey().size() == 1 && now.primaryKey().get(0).equals(f.name());
            if (values >= 10 && distinct == values && (exact || key) && !"boolean".equals(f.kind())) {
                add(out, existing, new Recommendation(f.name(), "unique", "uniqueness", f.name() + " is unique",
                    J.objectNode(), "All " + Measures.count(values) + " values are different"));
            }
            List<Counted> top = f.top();
            if ((f.text() || "int16".equals(f.kind()) || "int32".equals(f.kind())) && distinct > 0 && distinct <= 12
                && values >= 20 && top.size() == distinct && top.stream().mapToLong(Counted::count).sum() == values) {
                ObjectNode p = J.objectNode();
                p.putArray("values").addAll(top.stream().map(c -> J.textNode(c.item())).toList());
                add(out, existing, new Recommendation(f.name(), "allowed_values", "validity", f.name()
                    + " is one of " + distinct + " values", p, "Only " + distinct + " different values occur"));
            }
            if (f.text() && f.has("patterns") && values >= 20 && !(distinct > 0 && distinct <= 12)) {
                Patterns.Suggestion s = Patterns.suggest(f.patterns(), f.formats(), values);
                if (s != null && s.coverage() >= 0.99) {
                    add(out, existing, new Recommendation(f.name(), "matches_pattern", "validity", f.name()
                        + " matches its pattern", J.objectNode().put("regex", s.regex()), Measures.percent(s.coverage())
                        + " of values match " + s.regex() + " (" + s.basis() + ")"));
                }
            }
            if (f.numeric() && f.has("min") && f.has("max") && values >= 20) {
                add(out, existing, new Recommendation(f.name(), "in_range", "accuracy", f.name() + " stays in range",
                    J.objectNode().put("min", f.text("min")).put("max", f.text("max")), "Every value lies between "
                        + f.text("min") + " and " + f.text("max")));
            }
        }
        FieldFacts recency = Measures.recencyField(now);
        if (recency != null) {
            Duration cadence = Measures.cadence(recency.name(), now, history, defaultCadence);
            double hours = Math.max(1, Math.ceil(cadence.toMinutes() * 2 / 60.0));
            add(out, existing, new Recommendation(recency.name(), "freshness", "timeliness", recency.name()
                + " is recent", J.objectNode().put("maxAgeHours", hours), "New data usually arrives every "
                + Measures.hours(cadence) + "; this allows twice that"));
        }
        if (now.rowCount() != null && now.rowCount() > 0) {
            double band = Measures.band(history);
            add(out, existing, new Recommendation(null, "row_count_change", "volume", "Row count is steady",
                J.objectNode().put("maxChange", Measures.round(Math.max(0.1, band))), history.size() >= 4
                    ? "Changes have stayed within " + Measures.percent(band) : "A first limit; it can be tightened "
                    + "once more scans show the usual range"));
        }
        return out;
    }

    private static void add(List<Recommendation> out, Set<String> existing, Recommendation r) {
        if (!existing.contains(r.type() + ":" + (r.field() == null ? "" : r.field()))) {
            out.add(r);
        }
    }
}

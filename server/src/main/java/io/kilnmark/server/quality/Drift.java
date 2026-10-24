package io.kilnmark.server.quality;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.kilnmark.server.quality.ProfileFacts.Counted;
import io.kilnmark.server.quality.ProfileFacts.FieldFacts;

/**
 * Drift between two profile versions of an asset: the new one from a scan, and the previous
 * version or a baseline. Nothing is read again: both sides are stored profiles and sketches.
 */
public final class Drift {

    /**
     * One change worth recording. {@code field} is null for the asset as a whole. {@code message}
     * holds words, counts and shares only; {@code detail}, when not null, says the same with the
     * values involved and is shown only to roles that may see data.
     */
    public record Event(String field, String kind, String change, String severity, String message, String detail,
                        ObjectNode evidence) {
        public Event(String field, String kind, String change, String severity, String message, ObjectNode evidence) {
            this(field, kind, change, severity, message, null, evidence);
        }
    }

    private static final JsonNodeFactory J = JsonNodeFactory.instance;

    private Drift() {
    }

    /** Every change from {@code before} to {@code now}, with fixed volume thresholds and no freshness check. */
    public static List<Event> compare(ProfileFacts now, ProfileFacts before, Duration cadence) {
        return compare(now, before, cadence, null);
    }

    /**
     * Every change from {@code before} to {@code now}. {@code cadence} is how often new data is
     * expected, once learned (null: freshness is not judged yet); {@code band} the usual relative
     * change in row count, once learned (null: fixed thresholds of 10%, 20% and 50%).
     */
    public static List<Event> compare(ProfileFacts now, ProfileFacts before, Duration cadence, Double band) {
        List<Event> out = new ArrayList<>();
        schema(now, before, out);
        volume(now, before, band, out);
        Map<String, FieldFacts> old = new HashMap<>();
        before.fields().forEach(f -> old.put(f.name(), f));
        for (FieldFacts f : now.fields()) {
            FieldFacts o = old.get(f.name());
            if (o == null || !f.kind().equals(o.kind()) || f.stats() == null || o.stats() == null) {
                continue;
            }
            distribution(f, o, out);
        }
        if (cadence != null) {
            freshness(now, before, cadence, out);
        }
        return out;
    }

    static void schema(ProfileFacts now, ProfileFacts before, List<Event> out) {
        Map<String, FieldFacts> old = new HashMap<>();
        before.fields().forEach(f -> old.put(f.name(), f));
        Map<String, FieldFacts> cur = new HashMap<>();
        now.fields().forEach(f -> cur.put(f.name(), f));
        List<FieldFacts> added = now.fields().stream().filter(f -> !old.containsKey(f.name())).toList();
        List<FieldFacts> removed = before.fields().stream().filter(f -> !cur.containsKey(f.name())).toList();
        Set<String> renamedFrom = new HashSet<>();
        Set<String> renamedTo = new HashSet<>();
        for (FieldFacts r : removed) {
            for (FieldFacts a : added) {
                if (!renamedTo.contains(a.name()) && a.position() == r.position() && a.kind().equals(r.kind())
                    && similar(a, r)) {
                    renamedFrom.add(r.name());
                    renamedTo.add(a.name());
                    out.add(new Event(a.name(), "schema", "column_renamed", "medium",
                        "Column " + r.name() + " appears to have been renamed to " + a.name()
                            + " (same position, type and profile)",
                        J.objectNode().put("from", r.name()).put("to", a.name()).put("position", a.position())));
                    break;
                }
            }
        }
        for (FieldFacts r : removed) {
            if (!renamedFrom.contains(r.name())) {
                out.add(new Event(r.name(), "schema", "column_removed", "high", "Column " + r.name() + " was removed",
                    J.objectNode().put("kind", r.kind()).put("position", r.position())));
            }
        }
        for (FieldFacts a : added) {
            if (!renamedTo.contains(a.name())) {
                out.add(new Event(a.name(), "schema", "column_added", "low", "Column " + a.name() + " was added",
                    J.objectNode().put("kind", a.kind()).put("position", a.position())));
            }
        }
        for (FieldFacts f : now.fields()) {
            FieldFacts o = old.get(f.name());
            if (o != null && !f.kind().equals(o.kind())) {
                out.add(new Event(f.name(), "schema", "column_retyped", "high", "Column " + f.name() + " changed type from "
                    + o.sourceType() + " to " + f.sourceType(), J.objectNode().put("from", o.kind()).put("to", f.kind())
                    .put("fromSourceType", o.sourceType()).put("toSourceType", f.sourceType())));
            }
        }
    }

    /** Two columns' profiles look alike: null share and distinct count within 10%, when both were profiled. */
    private static boolean similar(FieldFacts a, FieldFacts b) {
        if (a.stats() == null || b.stats() == null) {
            return true; // position and type agree, and there is nothing else to compare
        }
        double na = a.rows() <= 0 ? 0 : (double) a.nulls() / a.rows();
        double nb = b.rows() <= 0 ? 0 : (double) b.nulls() / b.rows();
        long da = a.get("distinct");
        long db = b.get("distinct");
        return Math.abs(na - nb) <= 0.1 && (Math.max(da, db) <= 0 || Math.abs(da - db) <= 0.1 * Math.max(da, db));
    }

    static void volume(ProfileFacts now, ProfileFacts before, Double band, List<Event> out) {
        if (now.rowCount() == null || before.rowCount() == null) {
            return;
        }
        long a = before.rowCount();
        long b = now.rowCount();
        double change = a == 0 ? (b == 0 ? 0 : 1) : (double) (b - a) / a;
        // Once learned, the usual band sets the thresholds: beyond it, twice it, three times it.
        String severity = band == null ? severity(Math.abs(change), 0.1, 0.2, 0.5)
            : severity(Math.abs(change), band, 2 * band, 3 * band);
        if (severity != null) {
            ObjectNode e = J.objectNode().put("from", a).put("to", b).put("change", Measures.round(change));
            if (band != null) {
                e.put("usualBand", Measures.round(band));
            }
            out.add(new Event(null, "volume", "row_count", severity, "Row count changed by " + Measures.percent(change)
                + ", from " + Measures.count(a) + " to " + Measures.count(b)
                + (band == null ? "" : "; usually within " + Measures.percent(band)), e));
        }
    }

    static void distribution(FieldFacts f, FieldFacts o, List<Event> out) {
        if (f.rows() > 0 && o.rows() > 0) {
            double nf = (double) f.nulls() / f.rows();
            double no = (double) o.nulls() / o.rows();
            String sev = severity(Math.abs(nf - no), 0.05, 0.1, 0.2);
            if (sev != null) {
                out.add(new Event(f.name(), "distribution", "null_rate", sev, "Nulls in " + f.name() + " went from "
                    + Measures.percent(no) + " to " + Measures.percent(nf), J.objectNode().put("from", Measures.round(no))
                    .put("to", Measures.round(nf))));
            }
        }
        long df = f.get("distinct");
        long dOld = o.get("distinct");
        if (df >= 0 && dOld >= 10) {
            double change = (double) (df - dOld) / dOld;
            String sev = severity(Math.abs(change), 0.2, 0.5, Double.MAX_VALUE);
            if (sev != null) {
                out.add(new Event(f.name(), "distribution", "distinct_count", sev, "Distinct values in " + f.name()
                    + " changed by " + Measures.percent(change) + ", from " + Measures.count(dOld) + " to "
                    + Measures.count(df), J.objectNode().put("from", dOld).put("to", df)));
            }
        }
        double[] qf = f.quantiles();
        double[] qo = o.quantiles();
        if (qf != null && qo != null && qf.length > 2 && qo.length > 2) {
            double ks = ks(qo, qf);
            String sev = severity(ks, 0.1, 0.2, 0.3);
            if (sev != null) {
                out.add(new Event(f.name(), "distribution", "quantile_shift", sev, "The distribution of " + f.name()
                    + " shifted: up to " + Measures.percent(ks) + " of values moved across the old quantiles",
                    "Median " + f.text("median") + ", was " + o.text("median"),
                    J.objectNode().put("ks", Measures.round(ks)).put("medianFrom", o.text("median"))
                        .put("medianTo", f.text("median"))));
            }
        }
        List<Counted> tf = f.top();
        List<Counted> to = o.top();
        if (tf.size() >= 2 && to.size() >= 2) {
            Set<String> a = new HashSet<>();
            tf.forEach(c -> a.add(c.item()));
            Set<String> b = new HashSet<>();
            to.forEach(c -> b.add(c.item()));
            Set<String> both = new HashSet<>(a);
            both.retainAll(b);
            Set<String> any = new HashSet<>(a);
            any.addAll(b);
            double jaccard = (double) both.size() / any.size();
            boolean leaderChanged = !tf.get(0).item().equals(to.get(0).item());
            if (jaccard < 0.5 || leaderChanged) {
                out.add(new Event(f.name(), "distribution", "top_values", leaderChanged && jaccard < 0.5 ? "medium" : "low",
                    "The most common values of " + f.name() + " changed" + (leaderChanged
                        ? ", including the commonest" : ""),
                    leaderChanged ? "The commonest is now " + tf.get(0).item() + " (was " + to.get(0).item() + ")" : null,
                    J.objectNode().put("overlap", Measures.round(jaccard))));
            }
        }
        patterns(f, o, out);
    }

    /** Changes in the share of each value shape, when both versions report shapes the same way. */
    static void patterns(FieldFacts f, FieldFacts o, List<Event> out) {
        if (!f.has("patterns") || !o.has("patterns") || f.values() <= 0 || o.values() <= 0
            || !String.valueOf(f.text("patterns_collapsed")).equals(String.valueOf(o.text("patterns_collapsed")))) {
            return;
        }
        Map<String, Double> sf = shares(f.patterns(), f.values());
        Map<String, Double> so = shares(o.patterns(), o.values());
        Set<String> shapes = new HashSet<>(sf.keySet());
        shapes.addAll(so.keySet());
        String worstShape = null;
        double worst = 0;
        // A shape that was not there before says more than the shrinking of an old one.
        for (String s : shapes) {
            if (!so.containsKey(s) && sf.get(s) >= 0.05 && sf.get(s) > worst) {
                worst = sf.get(s);
                worstShape = s;
            }
        }
        if (worstShape == null) {
            for (String s : shapes) {
                double d = Math.abs(sf.getOrDefault(s, 0.0) - so.getOrDefault(s, 0.0));
                if (d > worst) {
                    worst = d;
                    worstShape = s;
                }
            }
        }
        String sev = severity(worst, 0.05, 0.1, 0.2);
        if (sev != null) {
            boolean isNew = !so.containsKey(worstShape);
            out.add(new Event(f.name(), "distribution", "pattern_share", isNew && "low".equals(sev) ? "medium" : sev,
                (isNew ? "A new value shape " + worstShape + " makes up " : "Values shaped " + worstShape + " went from "
                    + Measures.percent(so.getOrDefault(worstShape, 0.0)) + " to ")
                    + Measures.percent(sf.getOrDefault(worstShape, 0.0)) + " of " + f.name(),
                J.objectNode().put("shape", worstShape).put("from", Measures.round(so.getOrDefault(worstShape, 0.0)))
                    .put("to", Measures.round(sf.getOrDefault(worstShape, 0.0)))));
        }
    }

    private static Map<String, Double> shares(List<Counted> patterns, long values) {
        Map<String, Double> out = new HashMap<>();
        patterns.forEach(p -> out.put(p.item(), (double) p.count() / values));
        return out;
    }

    static void freshness(ProfileFacts now, ProfileFacts before, Duration cadence, List<Event> out) {
        FieldFacts f = Measures.recencyField(now);
        if (f == null) {
            return;
        }
        FieldFacts o = before.field(f.name());
        if (o == null || o.latest() == null) {
            return;
        }
        Instant latest = f.latest();
        Duration since = Duration.between(before.observedAt(), now.observedAt());
        if (!latest.isAfter(o.latest()) && since.compareTo(cadence) >= 0) {
            Duration age = Duration.between(latest, now.observedAt());
            String sev = age.compareTo(cadence.multipliedBy(3)) > 0 ? "high"
                : age.compareTo(cadence.multipliedBy(2)) > 0 ? "medium" : "low";
            out.add(new Event(null, "freshness", "no_new_data", sev, "No new data since the previous scan: the latest "
                + f.name() + " is " + Measures.hours(age) + " old", "The latest " + f.name() + " is still " + latest,
                J.objectNode().put("field", f.name()).put("latest", latest.toString())
                    .put("ageHours", Measures.round(age.toSeconds() / 3600.0))));
        }
    }

    /**
     * The Kolmogorov-Smirnov distance between two distributions given as quantiles at equal steps:
     * the largest gap between their cumulative shares, each read from its quantiles by linear
     * interpolation, at every quantile of either.
     */
    static double ks(double[] a, double[] b) {
        double worst = 0;
        for (double[] side : List.of(a, b)) {
            for (double x : side) {
                worst = Math.max(worst, Math.abs(cdf(a, x) - cdf(b, x)));
            }
        }
        return worst;
    }

    /** The share of values at or below {@code x}, from quantiles at equal steps. */
    static double cdf(double[] q, double x) {
        int n = q.length - 1;
        if (x < q[0]) {
            return 0;
        }
        if (x >= q[n]) {
            return 1;
        }
        // The last quantile at or below x; ties (many equal values) take the highest rank.
        int i = n - 1;
        while (i > 0 && q[i] > x) {
            i--;
        }
        double lo = q[i];
        double hi = q[i + 1];
        double frac = hi == lo ? 1 : (x - lo) / (hi - lo);
        return (i + frac) / n;
    }

    /** low, medium or high as {@code v} passes each threshold; null below all of them. */
    static String severity(double v, double low, double medium, double high) {
        if (v >= high) {
            return "high";
        }
        if (v >= medium) {
            return "medium";
        }
        return v >= low ? "low" : null;
    }
}

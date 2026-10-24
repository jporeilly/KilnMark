package io.kilnmark.server.quality;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.kilnmark.server.quality.ProfileFacts.Counted;
import io.kilnmark.server.quality.ProfileFacts.FieldFacts;

/**
 * Automatic pre-analysis: quality measures worked out from a profile version straight after the
 * scan, with no rules, no model and no extra read of the data. Each measure has a score from 0 to
 * 1, its evidence, and a reason in words. A dimension a column cannot be judged on gets no
 * measure rather than a guess.
 */
public final class Measures {

    /** A measure for a field ({@code ""} for the whole asset) in one dimension. */
    public record Measure(String field, Dimension dimension, double score, ObjectNode evidence, String reason) {
    }

    /**
     * Something about a relationship the asset takes part in: the share of this field's distinct
     * values not found in the other asset, estimated from both columns' stored sketches.
     */
    public record Orphans(String field, String otherAsset, String otherField, double orphanShare, boolean declared) {
    }

    private static final JsonNodeFactory J = JsonNodeFactory.instance;
    private static final Pattern KEY_NAME = Pattern.compile("(?i)(?:^|_)(?:id|key|code|uuid|guid|ref)$|[a-z]Id$");
    private static final Pattern NOT_NEGATIVE = Pattern.compile(
        "(?i)(?:^|_)(?:qty|quantity|age|count|price|units|stock|weight|height|length|width|duration)(?:$|_)");
    private static final Pattern BIRTH = Pattern.compile("(?i)birth|(?:^|_)dob(?:$|_)");
    private static final Pattern RECENCY = Pattern.compile(
        "(?i)(?:updated|modified|changed|created|loaded|inserted|ingested|event|ordered|occurred)|(?:_at|_ts|_on|_time|_date)$");
    private static final Pattern DATE_SHAPE = Pattern.compile("9{1,4}[-/. ]9{1,2}[-/. ]9{1,4}(?:[T ].*)?|9+[-/.]9+[-/.]9+");
    private static final Pattern DECIMAL_DOT = Pattern.compile("[-+]?9+(?:,9{3})*\\.9+");
    private static final Pattern DECIMAL_COMMA = Pattern.compile("[-+]?9+(?:\\.9{3})*,9+");
    /** Without history, a change in row count beyond this share counts against volume. */
    static final double DEFAULT_VOLUME_BAND = 0.5;

    private Measures() {
    }

    /**
     * Measures for {@code now}, given earlier versions (most recent first), the orphans found on
     * its relationships, and how often new data is expected when history cannot tell.
     */
    public static List<Measure> of(ProfileFacts now, List<ProfileFacts> history, List<Orphans> orphans,
                                   Duration defaultCadence) {
        List<Measure> out = new ArrayList<>();
        for (FieldFacts f : now.fields()) {
            if (f.stats() == null || f.rows() <= 0) {
                continue;
            }
            completeness(f, out);
            uniqueness(f, now.primaryKey(), out);
            validity(f, out);
            consistency(f, out);
            accuracy(f, out);
        }
        timeliness(now, history, defaultCadence, out);
        volume(now, history, out);
        for (Orphans o : orphans) {
            ObjectNode e = J.objectNode().put("otherAsset", o.otherAsset()).put("otherField", o.otherField())
                .put("orphanShare", round(o.orphanShare())).put("declared", o.declared());
            out.add(new Measure(o.field(), Dimension.INTEGRITY, clamp(1 - o.orphanShare()), e,
                percent(o.orphanShare()) + " of distinct values have no match in " + o.otherAsset() + "." + o.otherField()
                    + " (estimated from sketches)"));
        }
        return out;
    }

    static void completeness(FieldFacts f, List<Measure> out) {
        long blanks = Math.max(0, f.get("blanks"));
        long missing = f.nulls() + blanks;
        double share = (double) missing / f.rows();
        ObjectNode e = J.objectNode().put("rows", f.rows()).put("nulls", f.nulls()).put("blanks", blanks);
        out.add(new Measure(f.name(), Dimension.COMPLETENESS, clamp(1 - share), e, missing == 0 ? "Every row has a value"
            : count(missing) + " of " + count(f.rows()) + " rows are " + (blanks > 0 ? "null or blank" : "null")
                + " (" + percent(share) + ")"));
    }

    static void uniqueness(FieldFacts f, List<String> primaryKey, List<Measure> out) {
        long values = f.values();
        long distinct = f.get("distinct");
        if (values <= 0 || distinct < 0 || "boolean".equals(f.kind())) {
            return;
        }
        boolean declared = primaryKey.size() == 1 && primaryKey.get(0).equals(f.name());
        boolean exact = !"false".equals(f.text("distinct_exact"));
        double ratio = (double) distinct / values;
        boolean keyLike = KEY_NAME.matcher(f.name()).find() && ratio >= 0.9 && values >= 10;
        if (!declared && !keyLike) {
            return;
        }
        long duplicates = Math.max(0, values - distinct);
        // The distinct count is an estimate beyond a few thousand values (about 0.8% error):
        // a shortfall within that is not evidence of duplicates.
        if (!exact && duplicates < values * 0.02) {
            duplicates = 0;
        }
        double score = clamp(1 - (double) duplicates / values);
        ObjectNode e = J.objectNode().put("values", values).put("distinct", distinct).put("exact", exact)
            .put("declaredKey", declared).put("duplicates", duplicates);
        String reason = duplicates == 0 ? (declared ? "The declared key is unique" : "Looks like a key, and is unique")
            : (declared ? "The declared key has " : "Looks like a key but has ") + "about " + count(duplicates)
                + " repeated values" + (exact ? "" : " (estimated)");
        out.add(new Measure(f.name(), Dimension.UNIQUENESS, score, e, reason));
    }

    static void validity(FieldFacts f, List<Measure> out) {
        if (!f.text() || f.values() <= 0 || !f.has("patterns")) {
            return;
        }
        long values = f.values();
        List<Counted> patterns = f.patterns();
        if (!f.acceptedShapes().isEmpty()) {
            long matched = patterns.stream().filter(p -> f.acceptedShapes().contains(p.item())).mapToLong(Counted::count)
                .sum();
            double share = (double) matched / values;
            ObjectNode e = J.objectNode().put("values", values).put("matched", matched).put("basis", "accepted patterns");
            e.putArray("accepted").addAll(f.acceptedShapes().stream().sorted().map(J::textNode).toList());
            e.set("nonMatching", J.arrayNode().addAll(patterns.stream()
                .filter(p -> !f.acceptedShapes().contains(p.item())).limit(5)
                .map(p -> J.objectNode().put("shape", p.item()).put("count", p.count())).toList()));
            out.add(new Measure(f.name(), Dimension.VALIDITY, clamp(share), e, percent(share)
                + " of values match the accepted patterns"));
            return;
        }
        Map<String, Long> formats = f.formats();
        String best = null;
        long bestCount = 0;
        for (Map.Entry<String, Long> x : formats.entrySet()) {
            if (!x.getKey().equals("padded") && x.getValue() > bestCount) {
                best = x.getKey();
                bestCount = x.getValue();
            }
        }
        if (best == null || bestCount < values * 0.5) {
            return; // no format the column is clearly meant to have
        }
        double share = (double) bestCount / values;
        ObjectNode e = J.objectNode().put("values", values).put("matched", bestCount).put("basis", "detected format")
            .put("format", best);
        out.add(new Measure(f.name(), Dimension.VALIDITY, clamp(share), e, percent(share) + " of values are a valid "
            + best.replace('_', ' ') + "; accept the column's patterns to measure against them instead"));
    }

    static void consistency(FieldFacts f, List<Measure> out) {
        long values = f.values();
        if (values <= 0) {
            return;
        }
        List<String> problems = new ArrayList<>();
        double worst = 0;
        ObjectNode e = J.objectNode();
        if (f.text()) {
            List<Counted> patterns = f.patterns();
            double dates = competing(patterns, DATE_SHAPE, values);
            if (dates > 0) {
                problems.add("dates are written in several formats");
                e.put("competingDateFormats", round(dates));
                worst = Math.max(worst, dates);
            }
            long dot = patterns.stream().filter(p -> DECIMAL_DOT.matcher(p.item()).matches()).mapToLong(Counted::count).sum();
            long comma = patterns.stream().filter(p -> DECIMAL_COMMA.matcher(p.item()).matches())
                .mapToLong(Counted::count).sum();
            if (dot > 0 && comma > 0) {
                double share = (double) Math.min(dot, comma) / values;
                problems.add("numbers use both . and , as the decimal mark");
                e.put("mixedDecimalMarks", round(share));
                worst = Math.max(worst, share);
            }
            long padded = f.formats().getOrDefault("padded", 0L);
            if (padded > 0) {
                double share = (double) padded / values;
                problems.add(count(padded) + " values have leading or trailing spaces");
                e.put("padded", padded);
                worst = Math.max(worst, share);
            }
        }
        if (f.numeric() && NOT_NEGATIVE.matcher(f.name()).find() && f.get("negatives") > 0) {
            double share = (double) f.get("negatives") / values;
            problems.add(count(f.get("negatives")) + " negative values in a column that should not have any");
            e.put("negatives", f.get("negatives"));
            worst = Math.max(worst, share);
        }
        if (f.temporal() && BIRTH.matcher(f.name()).find() && f.get("after_now") > 0) {
            double share = (double) f.get("after_now") / values;
            problems.add("about " + count(f.get("after_now")) + " dates of birth are in the future");
            e.put("future", f.get("after_now"));
            worst = Math.max(worst, share);
        }
        boolean checkable = f.text() || (f.numeric() && NOT_NEGATIVE.matcher(f.name()).find())
            || (f.temporal() && BIRTH.matcher(f.name()).find());
        if (!checkable) {
            return;
        }
        e.put("values", values);
        out.add(new Measure(f.name(), Dimension.CONSISTENCY, clamp(1 - worst), e, problems.isEmpty()
            ? "No competing formats or impossible values found" : capitalise(String.join("; ", problems))));
    }

    /** The share of values in date-like shapes other than the most common one, if there are several. */
    private static double competing(List<Counted> patterns, Pattern kind, long values) {
        List<Counted> like = patterns.stream().filter(p -> kind.matcher(p.item()).matches()).toList();
        if (like.size() < 2) {
            return 0;
        }
        long total = like.stream().mapToLong(Counted::count).sum();
        long most = like.stream().mapToLong(Counted::count).max().orElse(0);
        // Shapes differing only in run lengths (9/9/9999 and 99/99/9999) are one format.
        long families = like.stream().map(p -> p.item().replaceAll("9+", "9")).distinct().count();
        if (families < 2) {
            return 0;
        }
        return (double) (total - most) / values;
    }

    static void accuracy(FieldFacts f, List<Measure> out) {
        long values = f.values();
        if (values <= 0) {
            return;
        }
        if (f.numeric() && f.get("outliers") >= 0) {
            long outliers = f.get("outliers");
            double share = (double) outliers / values;
            // Some values beyond the fences are normal; more than 1% counts against the column.
            double score = clamp(1 - Math.max(0, share - 0.01) * 2);
            ObjectNode e = J.objectNode().put("values", values).put("outliers", outliers)
                .put("fenceLow", f.text("fence_low")).put("fenceHigh", f.text("fence_high"))
                .put("min", f.text("min")).put("max", f.text("max"));
            out.add(new Measure(f.name(), Dimension.ACCURACY, score, e, outliers == 0
                ? "No values beyond the quartile fences"
                : "About " + count(outliers) + " values (" + percent(share) + ") lie beyond the quartile fences ("
                    + f.text("fence_low") + " to " + f.text("fence_high") + ")"));
            return;
        }
        List<Counted> top = f.top();
        long distinct = f.get("distinct");
        if (f.text() && distinct > 0 && distinct <= 20 && values >= 100 && !top.isEmpty()) {
            long usual = top.stream().mapToLong(Counted::count).sum();
            double outside = Math.max(0, (double) (values - usual) / values);
            if (outside > 0.05) {
                return; // not a small domain after all
            }
            ObjectNode e = J.objectNode().put("values", values).put("distinct", distinct).put("outsideDomain",
                values - usual);
            e.putArray("domain").addAll(top.stream().map(c -> J.textNode(c.item())).toList());
            out.add(new Measure(f.name(), Dimension.ACCURACY, clamp(1 - outside), e, outside == 0
                ? "Every value is one of " + top.size() + " usual values"
                : count(values - usual) + " values lie outside the " + top.size() + " usual values"));
        }
    }

    static void timeliness(ProfileFacts now, List<ProfileFacts> history, Duration defaultCadence, List<Measure> out) {
        FieldFacts recency = recencyField(now);
        if (recency == null) {
            return;
        }
        Instant latest = recency.latest();
        Duration cadence = cadence(recency.name(), now, history, defaultCadence);
        Duration age = Duration.between(latest, now.observedAt());
        double score;
        if (age.isNegative()) {
            score = 1; // the latest value is in the future: a consistency matter, not staleness
        } else if (age.compareTo(cadence) <= 0) {
            score = 1;
        } else {
            score = clamp(1 - (double) (age.toSeconds() - cadence.toSeconds()) / (3.0 * cadence.toSeconds()));
        }
        ObjectNode e = J.objectNode().put("field", recency.name()).put("latest", latest.toString())
            .put("ageHours", round(age.toSeconds() / 3600.0)).put("cadenceHours", round(cadence.toSeconds() / 3600.0));
        out.add(new Measure("", Dimension.TIMELINESS, score, e, score == 1 ? "New data within the expected "
            + hours(cadence) : "The latest " + recency.name() + " is " + hours(age) + " old; new data is expected every "
            + hours(cadence)));
    }

    /** The date or timestamp field that best says how recent the data is. */
    static FieldFacts recencyField(ProfileFacts p) {
        FieldFacts best = null;
        for (FieldFacts f : p.fields()) {
            if (!f.temporal() || f.latest() == null || BIRTH.matcher(f.name()).find()) {
                continue;
            }
            boolean named = RECENCY.matcher(f.name()).find();
            if (best == null || (named && !RECENCY.matcher(best.name()).find())
                || (named == RECENCY.matcher(best.name()).find() && f.latest().isAfter(best.latest()))) {
                best = f;
            }
        }
        return best;
    }

    /** How often new data arrives: the median gap between successive latest values, or the default. */
    static Duration cadence(String field, ProfileFacts now, List<ProfileFacts> history, Duration fallback) {
        List<Instant> points = new ArrayList<>();
        FieldFacts f = now.field(field);
        if (f != null && f.latest() != null) {
            points.add(f.latest());
        }
        for (ProfileFacts h : history) {
            FieldFacts g = h.field(field);
            if (g != null && g.latest() != null) {
                points.add(g.latest());
            }
        }
        List<Long> gaps = new ArrayList<>();
        for (int i = 0; i + 1 < points.size(); i++) {
            long s = Duration.between(points.get(i + 1), points.get(i)).toSeconds();
            if (s > 0) {
                gaps.add(s);
            }
        }
        if (gaps.size() < 2) {
            return fallback;
        }
        long[] sorted = gaps.stream().mapToLong(Long::longValue).sorted().toArray();
        return Duration.ofSeconds(Math.max(60, sorted[sorted.length / 2]));
    }

    static void volume(ProfileFacts now, List<ProfileFacts> history, List<Measure> out) {
        if (now.rowCount() == null || history.isEmpty()) {
            return;
        }
        ProfileFacts previous = history.get(0);
        if (previous.rowCount() == null) {
            return;
        }
        long before = previous.rowCount();
        long rows = now.rowCount();
        double change = before == 0 ? (rows == 0 ? 0 : 1) : (double) (rows - before) / before;
        double band = band(history);
        double over = Math.abs(change) - band;
        double score = over <= 0 ? 1 : clamp(1 - over / (2 * band));
        ObjectNode e = J.objectNode().put("rows", rows).put("previous", before).put("change", round(change))
            .put("usualBand", round(band));
        out.add(new Measure("", Dimension.VOLUME, score, e, over <= 0
            ? "Row count " + (change == 0 ? "unchanged" : "changed by " + percent(change)) + ", within the usual range"
            : "Row count changed by " + percent(change) + " since the last scan; usually within " + percent(band)));
    }

    /** The usual relative change: three standard deviations of past changes, at least 10%, or 50% without history. */
    static double band(List<ProfileFacts> history) {
        List<Double> changes = new ArrayList<>();
        for (int i = 0; i + 1 < history.size(); i++) {
            Long a = history.get(i).rowCount();
            Long b = history.get(i + 1).rowCount();
            if (a != null && b != null && b > 0) {
                changes.add((double) (a - b) / b);
            }
        }
        if (changes.size() < 3) {
            return DEFAULT_VOLUME_BAND;
        }
        double mean = changes.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double sd = Math.sqrt(changes.stream().mapToDouble(c -> (c - mean) * (c - mean)).sum() / changes.size());
        return Math.max(0.1, Math.abs(mean) + 3 * sd);
    }

    static double clamp(double v) {
        return Double.isNaN(v) ? 0 : Math.max(0, Math.min(1, v));
    }

    static double round(double v) {
        return Math.round(v * 10_000) / 10_000.0;
    }

    static String percent(double share) {
        double p = share * 100;
        String s = Math.abs(p) >= 10 || p == 0 ? String.format(Locale.ROOT, "%.0f", p)
            : String.format(Locale.ROOT, "%.1f", p);
        return s + "%";
    }

    static String count(long n) {
        return String.format(Locale.UK, "%,d", n);
    }

    static String hours(Duration d) {
        long h = Math.abs(d.toHours());
        return h >= 48 ? (h / 24) + " days" : h >= 1 ? h + (h == 1 ? " hour" : " hours")
            : Math.max(1, Math.abs(d.toMinutes())) + " minutes";
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** For tests: the dimensions measured for a field. */
    static List<Dimension> dimensions(List<Measure> measures, String field) {
        return measures.stream().filter(m -> m.field().equals(field)).map(Measure::dimension).toList();
    }

    static List<String> fields(List<Measure> measures) {
        return Arrays.asList(measures.stream().map(Measure::field).distinct().toArray(String[]::new));
    }
}

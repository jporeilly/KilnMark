package io.kilnmark.server.quality;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.kilnmark.graph.Urn;
import io.kilnmark.graph.store.Records;

/**
 * Kilnmark's quality model in open formats (ADR 0020):
 * <ul>
 * <li>an OpenLineage run event whose input dataset carries the {@code schema} facet and the
 * {@code dataQualityMetrics} and {@code dataQualityAssertions} input facets;</li>
 * <li>an Open Data Contract Standard 3.1 contract: the schema, and each rule in its
 * {@code quality} section.</li>
 * </ul>
 * Every profile statistic and rule has a place in each; what a format has no field for (a
 * Kilnmark dimension ODCS lacks, a rule's latest result, where a profile came from) travels in
 * the format's own extension point (a custom facet, {@code customProperties}), so an export read
 * back loses nothing.
 */
public final class StandardFormats {

    public static final String OPENLINEAGE_SPEC = "https://openlineage.io/spec/2-0-2/OpenLineage.json";
    static final String SCHEMA_FACET = "https://openlineage.io/spec/facets/1-1-1/SchemaDatasetFacet.json"
        + "#/$defs/SchemaDatasetFacet";
    static final String METRICS_FACET = "https://openlineage.io/spec/facets/1-0-2/DataQualityMetricsInputDatasetFacet.json"
        + "#/$defs/DataQualityMetricsInputDatasetFacet";
    static final String ASSERTIONS_FACET = "https://openlineage.io/spec/facets/1-0-1/DataQualityAssertionsDatasetFacet.json"
        + "#/$defs/DataQualityAssertionsDatasetFacet";
    /** Kilnmark's own facet: where the profile came from. Its schema URL names it; it is not published yet. */
    static final String PROVENANCE_FACET = "urn:kilnmark:facet:provenance:1";
    public static final String ODCS_VERSION = "v3.1.0";

    private static final JsonNodeFactory J = JsonNodeFactory.instance;
    private static final Set<String> NUMERIC = Set.of("int16", "int32", "int64", "float32", "float64", "decimal");

    /**
     * Everything an export needs about one asset, and who asks: {@code data} for roles that may see
     * data (otherwise allowed values, range bounds and custom SQL are left out), {@code unmasked}
     * for an admin who asked to unmask (otherwise statistics that are values, such as min, max and
     * quantiles, are left out for columns marked sensitive, and so are the allowed values and
     * range bounds of their rules).
     */
    public record Export(Records.AssetDetail asset, Records.Source source, QualityStore.Version version,
                         ProfileFacts facts, List<Rule> rules, Map<Long, QualityStore.Result> results,
                         Set<String> sensitive, String producer, boolean data, boolean unmasked) {
        public Export(Records.AssetDetail asset, Records.Source source, QualityStore.Version version,
                      ProfileFacts facts, List<Rule> rules, Map<Long, QualityStore.Result> results,
                      Set<String> sensitive, String producer) {
            this(asset, source, version, facts, rules, results, sensitive, producer, true, true);
        }

        /** Who asks, as {@link Masking} sees them. */
        public Masking.Sight sight() {
            return new Masking.Sight(data, sensitive, unmasked);
        }
    }

    private StandardFormats() {
    }

    // ---- OpenLineage ----

    /** An OpenLineage {@code COMPLETE} run event for the profiling run, with the asset as its input. */
    public static ObjectNode openLineage(Export x) {
        ObjectNode event = J.objectNode();
        event.put("eventType", "COMPLETE");
        Instant at = x.version() == null ? Instant.now() : x.version().observedAt();
        event.put("eventTime", at.toString());
        event.put("producer", x.producer());
        event.put("schemaURL", OPENLINEAGE_SPEC + "#/$defs/RunEvent");
        String runKey = x.asset().urn() + "#" + (x.version() == null ? "none" : x.version().id());
        event.putObject("run").put("runId", UUID.nameUUIDFromBytes(runKey.getBytes(StandardCharsets.UTF_8)).toString())
            .putObject("facets");
        event.putObject("job").put("namespace", "kilnmark").put("name", "profile." + x.source().name())
            .putObject("facets");
        ObjectNode input = event.putArray("inputs").addObject();
        String[] id = datasetId(x.source(), x.asset());
        input.put("namespace", id[0]);
        input.put("name", id[1]);
        ObjectNode facets = input.putObject("facets");
        ObjectNode schema = facet(facets.putObject("schema"), x.producer(), SCHEMA_FACET);
        ArrayNode fields = schema.putArray("fields");
        for (Records.FieldDetail f : x.asset().fields()) {
            ObjectNode o = fields.addObject().put("name", f.name()).put("type", f.sourceType());
            if (f.description() != null && !f.description().isBlank()) {
                o.put("description", f.description());
            }
        }
        ObjectNode inputFacets = input.putObject("inputFacets");
        if (x.facts() != null) {
            ObjectNode metrics = facet(inputFacets.putObject("dataQualityMetrics"), x.producer(), METRICS_FACET);
            if (x.facts().rowCount() != null) {
                metrics.put("rowCount", x.facts().rowCount());
            }
            ObjectNode columns = metrics.putObject("columnMetrics");
            for (ProfileFacts.FieldFacts f : x.facts().fields()) {
                if (f.stats() == null) {
                    continue;
                }
                ObjectNode c = columns.putObject(f.name());
                c.put("nullCount", Math.max(0, f.nulls()));
                if (f.get("distinct") >= 0) {
                    c.put("distinctCount", f.get("distinct"));
                }
                c.put("count", f.values());
                boolean hidden = x.sensitive().contains(f.name()) && !x.unmasked();
                if (NUMERIC.contains(f.kind()) && !hidden) {
                    putNumber(c, "sum", f.number("sum"));
                    putNumber(c, "min", f.number("min"));
                    putNumber(c, "max", f.number("max"));
                    double[] q = f.quantiles();
                    if (q != null) {
                        ObjectNode quantiles = c.putObject("quantiles");
                        for (int i = 0; i < q.length; i++) {
                            quantiles.put(quantileKey((double) i / (q.length - 1)), q[i]);
                        }
                    }
                }
            }
            ObjectNode prov = facet(inputFacets.putObject("kilnmark_provenance"), x.producer(), PROVENANCE_FACET);
            prov.put("origin", x.version().origin());
            prov.put("observedAt", x.version().observedAt().toString());
            prov.put("method", x.version().method());
            prov.put("estimate", x.version().estimate());
            prov.put("urn", x.asset().urn());
        }
        if (!x.rules().isEmpty()) {
            ObjectNode assertions = facet(inputFacets.putObject("dataQualityAssertions"), x.producer(), ASSERTIONS_FACET);
            ArrayNode list = assertions.putArray("assertions");
            for (Rule r : x.rules()) {
                QualityStore.Result res = x.results().get(r.id());
                if (res == null || res.status() == Rule.Status.ERROR) {
                    continue; // never evaluated, or could not run: no assertion either way
                }
                ObjectNode a = list.addObject().put("assertion", r.type().id())
                    .put("success", res.status() != Rule.Status.FAIL);
                if (r.field() != null) {
                    a.put("column", r.field());
                }
            }
        }
        event.putArray("outputs");
        return event;
    }

    private static ObjectNode facet(ObjectNode facet, String producer, String schemaUrl) {
        return facet.put("_producer", producer).put("_schemaURL", schemaUrl);
    }

    private static void putNumber(ObjectNode o, String key, double v) {
        if (!Double.isNaN(v) && !Double.isInfinite(v)) {
            o.put(key, v);
        }
    }

    static String quantileKey(double q) {
        String s = String.format(Locale.ROOT, "%.2f", q);
        return s.endsWith("0") ? s.substring(0, s.length() - 1) : s;
    }

    /**
     * The dataset's namespace and name, following OpenLineage's naming conventions where the
     * source says enough ({@code postgres://host:port} and {@code db.schema.table}); otherwise
     * {@code kilnmark://source} and the asset's path.
     */
    static String[] datasetId(Records.Source source, Records.AssetDetail asset) {
        String url = source.settings().get("url");
        try {
            if ("postgresql".equals(source.kind()) && url != null && url.startsWith("jdbc:postgresql://")) {
                URI u = URI.create(url.substring("jdbc:".length()));
                String db = u.getPath() == null ? "" : u.getPath().replaceFirst("^/", "");
                int port = u.getPort() < 0 ? 5432 : u.getPort();
                return new String[] {"postgres://" + u.getHost() + ":" + port, db + "." + asset.container() + "." + asset.name()};
            }
            if ("s3".equals(source.kind()) && source.settings().get("bucket") != null) {
                return new String[] {"s3://" + source.settings().get("bucket"), Urn.parse(asset.urn()).path()};
            }
        } catch (IllegalArgumentException e) {
            // fall through to Kilnmark's own naming
        }
        return new String[] {"kilnmark://" + source.name(), Urn.parse(asset.urn()).path()};
    }

    // ---- ODCS ----

    /** An ODCS 3.1 data contract describing the asset as it is: schema, quality rules and provenance. */
    public static ObjectNode odcs(Export x) {
        ObjectNode c = J.objectNode();
        c.put("apiVersion", ODCS_VERSION);
        c.put("kind", "DataContract");
        c.put("id", UUID.nameUUIDFromBytes(x.asset().urn().getBytes(StandardCharsets.UTF_8)).toString());
        c.put("name", x.asset().name());
        c.put("version", "1.0.0");
        c.put("status", x.asset().removedAt() == null ? "active" : "retired");
        if (x.asset().description() != null && !x.asset().description().isBlank()) {
            c.putObject("description").put("purpose", x.asset().description());
        }
        ObjectNode object = c.putArray("schema").addObject();
        object.put("name", x.asset().name());
        object.put("physicalName", x.asset().container().isEmpty() ? x.asset().name()
            : x.asset().container() + "." + x.asset().name());
        object.put("physicalType", x.asset().kind());
        object.put("logicalType", "object");
        if (x.asset().description() != null && !x.asset().description().isBlank()) {
            object.put("description", x.asset().description());
        }
        ArrayNode properties = object.putArray("properties");
        List<String> pk = x.asset().primaryKey();
        for (Records.FieldDetail f : x.asset().fields()) {
            ObjectNode p = properties.addObject();
            p.put("name", f.name());
            p.put("physicalName", f.name());
            p.put("physicalType", f.sourceType());
            String logical = logicalType(f.kind());
            if (logical != null) {
                p.put("logicalType", logical);
            }
            p.put("required", !f.nullable());
            if (pk.contains(f.name())) {
                p.put("primaryKey", true);
                p.put("primaryKeyPosition", pk.indexOf(f.name()) + 1);
            }
            if (f.description() != null && !f.description().isBlank()) {
                p.put("description", f.description());
            }
            if (x.sensitive().contains(f.name())) {
                p.put("classification", "sensitive");
            }
            ArrayNode quality = J.arrayNode();
            for (Rule r : x.rules()) {
                if (f.name().equals(r.field())) {
                    quality.add(quality(r, x.results().get(r.id()), x.sight()));
                }
            }
            if (!quality.isEmpty()) {
                p.set("quality", quality);
            }
        }
        ArrayNode tableQuality = J.arrayNode();
        for (Rule r : x.rules()) {
            if (r.field() == null || x.asset().fields().stream().noneMatch(f -> f.name().equals(r.field()))) {
                tableQuality.add(quality(r, x.results().get(r.id()), x.sight()));
            }
        }
        if (!tableQuality.isEmpty()) {
            object.set("quality", tableQuality);
        }
        ArrayNode custom = c.putArray("customProperties");
        custom.addObject().put("property", "kilnmarkUrn").put("value", x.asset().urn());
        custom.addObject().put("property", "originCatalogue").put("value", x.version() == null ? "kilnmark"
            : x.version().origin());
        if (x.version() != null) {
            custom.addObject().put("property", "observedAt").put("value", x.version().observedAt().toString());
            custom.addObject().put("property", "profileMethod").put("value", x.version().method());
            if (x.version().rowCount() != null) {
                custom.addObject().put("property", "rowCount").put("value", x.version().rowCount());
            }
        }
        c.put("contractCreatedTs", Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());
        return c;
    }

    static String logicalType(String kind) {
        return switch (kind == null ? "" : kind) {
            case "text" -> "string";
            case "int16", "int32", "int64" -> "integer";
            case "float32", "float64", "decimal" -> "number";
            case "boolean" -> "boolean";
            case "date" -> "date";
            case "time" -> "time";
            case "timestamp", "timestamp_tz" -> "timestamp";
            default -> null;
        };
    }

    /** One rule in ODCS's quality section: a library metric where ODCS has one, SQL, or Kilnmark's own. */
    static ObjectNode quality(Rule r, QualityStore.Result last) {
        return quality(r, last, true);
    }

    /**
     * One rule in ODCS's quality section; without {@code data}, no allowed values, range bounds
     * or custom SQL. Custom SQL's {@code ${table}} and {@code ${column}} become ODCS's
     * {@code {object}} and {@code {property}}.
     */
    static ObjectNode quality(Rule r, QualityStore.Result last, boolean data) {
        return quality(r, last, new Masking.Sight(data, Set.of(), true));
    }

    /**
     * One rule in ODCS's quality section as {@code sight} may see it: allowed values and range
     * bounds only where the rule's column's values may be shown, custom SQL only to roles that may
     * see data.
     */
    static ObjectNode quality(Rule r, QualityStore.Result last, Masking.Sight sight) {
        boolean data = sight.data();
        ObjectNode q = J.objectNode();
        q.put("id", "rule-" + r.id());
        q.put("name", r.name());
        q.put("description", r.dimension().label() + " check: " + r.type().id().replace('_', ' ')
            + (r.field() == null ? "" : " on " + r.field()));
        q.put("dimension", r.dimension().odcs());
        q.put("severity", "error");
        boolean share = "share".equals(r.thresholdUnit()) && !r.type().wholeAsset();
        double limit = share ? r.failAbove() * 100 : r.failAbove();
        JsonNode p = r.params();
        switch (r.type()) {
            case NOT_NULL -> library(q, p.path("blankIsMissing").asBoolean(false) ? "missingValues" : "nullValues",
                share, limit, null);
            case UNIQUE -> {
                ObjectNode args = null;
                if (p.has("fields")) {
                    args = J.objectNode();
                    args.set("properties", p.get("fields").deepCopy());
                }
                library(q, "duplicateValues", share, limit, args);
            }
            case ALLOWED_VALUES -> {
                ObjectNode args = null;
                if (sight.values(r.field())) {
                    args = J.objectNode();
                    args.set("validValues", p.get("values").deepCopy());
                }
                library(q, "invalidValues", share, limit, args);
            }
            case MATCHES_PATTERN -> library(q, "invalidValues", share, limit,
                J.objectNode().put("pattern", p.path("regex").asText()));
            case ROW_COUNT -> {
                q.put("type", "library");
                q.put("metric", "rowCount");
                q.put("unit", "rows");
                boolean min = p.hasNonNull("min");
                boolean max = p.hasNonNull("max");
                if (min && max && p.get("min").asLong() != p.get("max").asLong()) {
                    q.putArray("mustBeBetween").add(p.get("min").asLong()).add(p.get("max").asLong());
                } else if (min && max) {
                    q.put("mustBe", p.get("min").asLong());
                } else if (min) {
                    q.put("mustBeGreaterOrEqualTo", p.get("min").asLong());
                } else {
                    q.put("mustBeLessOrEqualTo", p.get("max").asLong());
                }
            }
            case CUSTOM_SQL -> {
                if (!data) {
                    q.put("type", "text"); // the SQL itself may quote values
                } else if (share) {
                    kilnmark(q, r, sight);
                } else {
                    q.put("type", "sql");
                    q.put("query", "SELECT COUNT(*) FROM (" + odcsPlaceholders(p.path("sql").asText()) + ") failing");
                    q.put("unit", "rows");
                    q.put("mustBeLessOrEqualTo", limit);
                }
            }
            default -> kilnmark(q, r, sight);
        }
        ArrayNode custom = q.putArray("customProperties");
        custom.addObject().put("property", "kilnmarkRuleType").put("value", r.type().id());
        if (r.dimension().odcsDiffers()) {
            custom.addObject().put("property", "kilnmarkDimension").put("value", r.dimension().id());
        }
        custom.addObject().put("property", "thresholdUnit").put("value", r.thresholdUnit());
        if (r.warnAbove() != null) {
            custom.addObject().put("property", "warnAbove").put("value", r.warnAbove());
        }
        custom.addObject().put("property", "failAbove").put("value", r.failAbove());
        custom.addObject().put("property", "originCatalogue").put("value", r.origin());
        if (last != null) {
            ObjectNode v = J.objectNode().put("status", last.status().id()).put("observedAt", last.observedAt().toString())
                .put("method", last.method());
            if (last.failing() != null) {
                v.put("failing", last.failing());
            }
            if (last.total() != null) {
                v.put("total", last.total());
            }
            custom.addObject().put("property", "lastResult").set("value", v);
        }
        return q;
    }

    private static void library(ObjectNode q, String metric, boolean share, double limit, ObjectNode args) {
        q.put("type", "library");
        q.put("metric", metric);
        q.put("unit", share ? "percent" : "rows");
        if (args != null) {
            q.set("arguments", args);
        }
        q.put("mustBeLessOrEqualTo", limit);
    }

    /** Kilnmark's {@code ${table}} and {@code ${column}} as ODCS writes them: {@code {object}} and {@code {property}}. */
    static String odcsPlaceholders(String sql) {
        return SqlRules.placeholders(sql, "{object}", "{property}");
    }

    private static void kilnmark(ObjectNode q, Rule r, Masking.Sight sight) {
        boolean data = sight.data();
        q.put("type", "custom");
        q.put("engine", "kilnmark");
        ObjectNode impl = J.objectNode().put("type", r.type().id());
        if (r.field() != null) {
            impl.put("field", r.field());
        }
        JsonNode params = Masking.params(r, sight).deepCopy();
        if (data && r.type() == RuleType.CUSTOM_SQL && params instanceof ObjectNode o && o.has("sql")) {
            o.put("sql", odcsPlaceholders(o.get("sql").asText()));
        }
        impl.set("params", params);
        impl.put("thresholdUnit", r.thresholdUnit());
        impl.put("failAbove", r.failAbove());
        if (r.warnAbove() != null) {
            impl.put("warnAbove", r.warnAbove());
        }
        q.set("implementation", impl);
    }
}

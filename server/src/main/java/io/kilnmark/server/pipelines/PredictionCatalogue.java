package io.kilnmark.server.pipelines;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kilnmark.graph.store.RelationshipStore;
import io.kilnmark.kiln.plugins.relate.Containment;
import io.kilnmark.server.quality.ProfileFacts;
import io.kilnmark.server.quality.QualityService;
import io.kilnmark.server.quality.QualityStore;
import io.kilnmark.server.quality.Rule;
import io.kilnmark.server.quality.Sights;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The catalogue as quality prediction reads it ({@link QualityPredictor.Catalogue}): latest
 * profiles with accepted shapes, enabled rules and their last results, and relationships with
 * their orphan shares. Reads stored metadata only, never a source. Each {@link #open()} gives a
 * view that remembers what it read, so one prediction asks for each asset once.
 */
@ApplicationScoped
public class PredictionCatalogue {

    private static final Logger LOG = Logger.getLogger(PredictionCatalogue.class);
    /** Most relationships looked at per asset. */
    static final int MAX_LINKS = 100;

    @Inject
    QualityService quality;

    @Inject
    QualityStore store;

    @Inject
    RelationshipStore relations;

    @Inject
    ObjectMapper json;

    /**
     * Profile statistics that hold values from the data, or tell them by the shape of an estimate
     * (a filter's predicted rows against a bound): most common values and the spread. Withheld from a
     * caller who may not see them ({@link #withheld}), so those estimates become unknown.
     */
    static final Set<String> VALUE_STATS = Set.of("top", "quantiles", "fence_low", "fence_high", "min", "max",
        "median", "p25", "p75", "sum");

    /** A view for one prediction, remembering what it read, for the server's own use (a run starting). */
    Remembering open() {
        return new Remembering(null);
    }

    /**
     * A view for one prediction asked for by {@code sights}' caller: a column's values (most common
     * values, quantiles) are used only when the caller may see them, as the quality views decide:
     * not at all for a role that may not see data, and for a column marked sensitive only for an admin.
     */
    Remembering open(Sights sights) {
        return new Remembering(sights);
    }

    /**
     * The facts with each field's value-bearing statistics removed where {@code data} is false, or
     * the field is in {@code sensitive} and {@code unmask} is false.
     */
    static ProfileFacts withheld(ProfileFacts f, boolean data, Set<String> sensitive, boolean unmask) {
        if (f == null || (data && (unmask || sensitive.isEmpty()))) {
            return f;
        }
        List<ProfileFacts.FieldFacts> fields = new ArrayList<>();
        for (ProfileFacts.FieldFacts x : f.fields()) {
            boolean hide = !data || (!unmask && sensitive.contains(x.name()));
            if (!hide || x.stats() == null || !x.stats().isObject()) {
                fields.add(x);
                continue;
            }
            com.fasterxml.jackson.databind.node.ObjectNode s = ((com.fasterxml.jackson.databind.node.ObjectNode) x.stats())
                .deepCopy();
            s.remove(VALUE_STATS);
            fields.add(new ProfileFacts.FieldFacts(x.name(), x.position(), x.kind(), x.sourceType(), x.nullable(), s,
                x.acceptedShapes()));
        }
        return new ProfileFacts(f.versionId(), f.assetUrn(), f.observedAt(), f.rowCount(), f.rowCountComplete(),
            f.estimate(), f.primaryKey(), fields);
    }

    /** The facts of an asset's latest profile, as stored. */
    Optional<ProfileFacts> latest(String urn) {
        return quality.latestFacts(urn);
    }

    final class Remembering implements QualityPredictor.Catalogue {
        private final Sights sights;

        Remembering(Sights sights) {
            this.sights = sights;
        }

        private final Map<String, Optional<ProfileFacts>> facts = new HashMap<>();
        private final Map<String, List<Rule>> rules = new HashMap<>();
        private final Map<String, Map<Long, QualityPredictor.LastResult>> results = new HashMap<>();
        private final Map<String, Double> orphans = new HashMap<>();
        private final Map<String, List<QualityPredictor.Link>> links = new HashMap<>();

        @Override
        public ProfileFacts facts(String urn) {
            return facts.computeIfAbsent(urn, u -> {
                try {
                    Optional<ProfileFacts> f = latest(u);
                    if (sights == null || f.isEmpty()) {
                        return f;
                    }
                    boolean data = sights.data();
                    boolean unmask = sights.mayUnmask();
                    return Optional.ofNullable(withheld(f.get(), data, data && !unmask ? sights.marks(u) : Set.of(),
                        unmask));
                } catch (RuntimeException e) {
                    LOG.debugf("No profile of %s for prediction: %s", u, e.getMessage());
                    return Optional.empty();
                }
            }).orElse(null);
        }

        @Override
        public List<Rule> rules(String urn) {
            return rules.computeIfAbsent(urn, u -> store.rules(u, null, true));
        }

        @Override
        public Map<Long, QualityPredictor.LastResult> lastResults(String urn) {
            return results.computeIfAbsent(urn, u -> {
                Map<Long, QualityPredictor.LastResult> out = new LinkedHashMap<>();
                store.latestResults(u).forEach((id, r) -> out.put(id, new QualityPredictor.LastResult(
                    r.failing() == null ? 0 : r.failing(), r.total() == null ? 0 : r.total(), r.status())));
                return out;
            });
        }

        @Override
        public Double orphanShare(String fromUrn, String fromField, String toUrn, String toField) {
            String key = fromUrn + "\u0000" + fromField + "\u0000" + toUrn + "\u0000" + toField;
            if (orphans.containsKey(key)) {
                return orphans.get(key);
            }
            Double share = null;
            for (QualityPredictor.Link l : links(fromUrn)) {
                if (l.field().equals(fromField) && l.toUrn().equals(toUrn) && l.toField().equals(toField)
                    && l.orphanShare() != null) {
                    share = l.orphanShare();
                    break;
                }
            }
            if (share == null) {
                share = fromSketches(fromUrn, fromField, toUrn, toField);
            }
            orphans.put(key, share);
            return share;
        }

        @Override
        public List<String> acceptedRegexes(String urn, String field) {
            List<String> out = new ArrayList<>();
            try {
                for (QualityStore.Accepted a : store.accepted(urn)) {
                    if (a.field().equals(field) && a.regex() != null && !a.regex().isBlank()) {
                        out.add(a.regex());
                    }
                }
            } catch (RuntimeException e) {
                LOG.debugf("No accepted patterns of %s: %s", urn, e.getMessage());
            }
            return out;
        }

        @Override
        public List<QualityPredictor.Link> links(String urn) {
            return links.computeIfAbsent(urn, u -> {
                List<QualityPredictor.Link> out = new ArrayList<>();
                try {
                    for (RelationshipStore.Relationship r : relations.among(List.of(u), true, MAX_LINKS)) {
                        if (!r.from().urn().equals(u) || r.from().fields().size() != 1 || r.to().fields().size() != 1
                            || "rejected".equals(r.status())) {
                            continue;
                        }
                        String field = r.from().fields().get(0);
                        String toField = r.to().fields().get(0);
                        Double share = containment(r.evidenceJson());
                        if (share == null) {
                            share = fromSketches(u, field, r.to().urn(), toField);
                        }
                        out.add(new QualityPredictor.Link(field, r.to().urn(), toField, share, r.kind(), r.status()));
                    }
                } catch (RuntimeException e) {
                    LOG.debugf("No relationships of %s for prediction: %s", u, e.getMessage());
                }
                return out;
            });
        }

        private Double containment(String evidence) {
            if (evidence == null) {
                return null;
            }
            try {
                JsonNode e = json.readTree(evidence);
                return e.hasNonNull("containment") ? Math.max(0, 1 - e.get("containment").asDouble()) : null;
            } catch (Exception ex) {
                return null;
            }
        }

        private Double fromSketches(String fromUrn, String fromField, String toUrn, String toField) {
            try {
                RelationshipStore.FieldRef a = new RelationshipStore.FieldRef(fromUrn, fromField);
                RelationshipStore.FieldRef b = new RelationshipStore.FieldRef(toUrn, toField);
                Map<RelationshipStore.FieldRef, byte[]> s = relations.sketches(List.of(a, b));
                if (s.get(a) == null || s.get(b) == null) {
                    return null;
                }
                Containment c = Containment.of(s.get(a), s.get(b));
                return c.conclusive() ? Math.max(0, 1 - c.fraction()) : null;
            } catch (RuntimeException e) {
                return null;
            }
        }
    }
}

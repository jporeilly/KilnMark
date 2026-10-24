package io.kilnmark.server.relations;

import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.kilnmark.graph.Urn;
import io.kilnmark.graph.store.Records;
import io.kilnmark.graph.store.RelationshipStore;
import io.kilnmark.kiln.MetadataEvent;
import io.kilnmark.kiln.connect.RelationshipInfo;
import io.kilnmark.kiln.plugins.profile.ProfileStep;
import io.kilnmark.kiln.plugins.relate.KeyInference;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Keeps relationships up to date as scans run: stores each column's sketch as it is profiled,
 * and after a scan records the source's declared foreign keys and refreshes the relationships
 * inferred between its columns and every other source's. Inferred ones are proposals: nothing is
 * accepted without a person.
 */
@ApplicationScoped
public class RelationshipService {

    private static final Logger LOG = Logger.getLogger(RelationshipService.class);

    /** Most columns read for inference after one scan; beyond this the rest are left out and logged. */
    static final int MAX_KEY_COLUMNS = 200_000;

    private final RelationshipStore store;
    private final ObjectMapper json;
    private final KeyInference inference = new KeyInference(KeyInference.Limits.DEFAULT);

    public RelationshipService(RelationshipStore store, ObjectMapper json) {
        this.store = store;
        this.json = json;
    }

    /** True for a profile attribute that is stored apart from the profile JSON. */
    public static boolean storedApart(String attribute) {
        return ProfileStep.SKETCH.equals(attribute);
    }

    /**
     * The fields of {@code asset} whose sketch is worth keeping: those inference can use, either
     * end of a relationship. An identifier-like name ({@code id}, {@code customer_id}, {@code order_no})
     * or the asset's whole single-column primary key. As {@link ProfileStep#SKETCH_FIELDS} wants them.
     */
    public static String sketchFields(io.kilnmark.kiln.connect.AssetInfo asset) {
        List<String> fields = asset.fields().stream().map(io.kilnmark.kiln.connect.AssetInfo.FieldInfo::name)
            .filter(f -> io.kilnmark.kiln.plugins.relate.KeyNames.idLike(f)
                || asset.primaryKey().size() == 1 && asset.primaryKey().get(0).equals(f))
            .toList();
        return ProfileStep.sketchFields(fields);
    }

    /** Stores the sketch a {@code PROFILE} event carries, if any, with the profile of run {@code runId}. */
    public void saveSketch(MetadataEvent e, long runId) {
        String encoded = e.attributes().get(ProfileStep.SKETCH);
        if (encoded == null) {
            return;
        }
        try {
            store.saveSketch(e.assetUrn(), e.attributes().get("field"), runId, Base64.getDecoder().decode(encoded));
        } catch (IllegalArgumentException ex) {
            LOG.warnf("Ignored an unreadable sketch for %s", e.assetUrn());
        }
    }

    /** What {@link #afterScan} did, for the run's summary. */
    public record Outcome(int declared, int proposed, int removed) {
    }

    /**
     * After a scan of {@code source}: records the foreign keys it declares, then refreshes the
     * inferred relationships with at least one end in it.
     */
    public Outcome afterScan(Records.Source source, List<RelationshipInfo> declared) {
        return store.serialised(() -> refresh(source, declared));
    }

    private Outcome refresh(Records.Source source, List<RelationshipInfo> declared) {
        int saved = store.saveDeclared(source.id(), declared.stream().map(r -> new RelationshipStore.Declared(
            urn(source, r.fromPath()), r.fromFields(), urn(source, r.toPath()), r.toFields(), r.name())).toList());
        List<RelationshipStore.KeyColumn> columns = store.keyColumns(source.id(), MAX_KEY_COLUMNS);
        if (columns.size() == MAX_KEY_COLUMNS) {
            LOG.warnf("Relationship discovery read only the first %d key-like columns of the catalogue", MAX_KEY_COLUMNS);
        }
        KeyInference.Result r = inference.infer(columns.stream().map(RelationshipService::column).toList(),
            c -> c.source().equals(source.name()), this::sketches);
        if (r.pairsSkipped() > 0) {
            LOG.infof("Relationship discovery for %s scored %d candidate pairs and skipped %d", source.name(),
                r.pairsScored(), r.pairsSkipped());
        }
        RelationshipStore.Refresh refresh = store.refreshInferred(source.id(), r.proposals().stream()
            .map(p -> new RelationshipStore.Inferred(p.from().assetUrn(), p.from().field(), p.to().assetUrn(),
                p.to().field(), p.confidence(), evidence(p.evidence())))
            .toList());
        return new Outcome(saved, refresh.added(), refresh.removed());
    }

    private static String urn(Records.Source source, String path) {
        return new Urn(source.name(), path).toString();
    }

    private static KeyInference.Column column(RelationshipStore.KeyColumn k) {
        return new KeyInference.Column(k.assetUrn(), k.source(), k.assetName(), k.field(), k.kind(), k.primaryKey(),
            k.profiled(), k.rows(), k.nulls(), k.distinct());
    }

    private Map<KeyInference.ColumnRef, byte[]> sketches(Set<KeyInference.ColumnRef> refs) {
        Map<KeyInference.ColumnRef, byte[]> out = new HashMap<>();
        store.sketches(refs.stream().map(r -> new RelationshipStore.FieldRef(r.assetUrn(), r.field())).toList())
            .forEach((k, v) -> out.put(new KeyInference.ColumnRef(k.urn(), k.field()), v));
        return out;
    }

    private String evidence(KeyInference.Evidence e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rule", e.rule());
        m.put("nameScore", e.nameScore());
        m.put("containment", e.containment());
        m.put("containmentMargin", e.containmentMargin());
        m.put("fromDistinct", e.fromDistinct());
        m.put("toDistinct", e.toDistinct());
        m.put("toRows", e.toRows());
        m.put("unique", e.unique());
        m.put("fromType", e.fromType());
        m.put("toType", e.toType());
        m.put("method", "names, then HyperLogLog containment from stored sketches");
        try {
            return json.writeValueAsString(m);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}

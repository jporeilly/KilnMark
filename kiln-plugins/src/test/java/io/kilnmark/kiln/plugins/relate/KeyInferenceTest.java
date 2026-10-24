package io.kilnmark.kiln.plugins.relate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;

import org.apache.datasketches.hll.HllSketch;
import org.apache.datasketches.hll.TgtHllType;
import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.plugins.relate.KeyInference.Column;
import io.kilnmark.kiln.plugins.relate.KeyInference.ColumnRef;
import io.kilnmark.kiln.plugins.relate.KeyInference.Proposal;

class KeyInferenceTest {

    /** A sketch made as the profiler makes it: lgK 14, HLL_8 while profiling, stored as compact HLL_4. */
    static byte[] sketch(LongStream values) {
        HllSketch s = new HllSketch(14, TgtHllType.HLL_8);
        values.forEach(s::update);
        return s.copyAs(TgtHllType.HLL_4).toCompactByteArray();
    }

    static byte[] textSketch(List<String> values) {
        HllSketch s = new HllSketch(14, TgtHllType.HLL_8);
        values.forEach(v -> s.update(v.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return s.copyAs(TgtHllType.HLL_4).toCompactByteArray();
    }

    static Column col(String urn, String field, String kind, boolean pk, long rows, long distinct) {
        String name = urn.substring(urn.lastIndexOf(':') + 1);
        return new Column(urn, urn.split(":")[2], name, field, kind, pk, true, rows, 0, distinct);
    }

    private final Map<ColumnRef, byte[]> sketches = new HashMap<>();

    private KeyInference.Result infer(List<Column> columns) {
        return new KeyInference(KeyInference.Limits.DEFAULT).infer(columns, c -> true, refs -> sketches);
    }

    @Test
    void namesCompareWithoutCaseOrUnderscores() {
        assertEquals("customerid", KeyNames.normal("Customer_ID"));
        assertEquals("customer", KeyNames.singular("customers"));
        assertEquals("category", KeyNames.singular("categories"));
        assertEquals("address", KeyNames.singular("addresses"));
        assertEquals("box", KeyNames.singular("boxes"));
        assertEquals("status", KeyNames.singular("status"));
        assertEquals("customer", KeyNames.entity("crm.Customers"));
        assertEquals("customersexport2025", KeyNames.entity("exports/customers_export_2025.csv"));
        assertEquals("customer", KeyNames.entity("customers.parquet"));
        assertTrue(KeyNames.idLike("customerId"));
        assertTrue(KeyNames.idLike("order_no"));
        assertFalse(KeyNames.idLike("email"));
        assertEquals("number", KeyNames.suffix("ordernumber"));
    }

    @Test
    void containmentFromRealSketches() {
        byte[] customers = sketch(LongStream.rangeClosed(1, 5_000));
        Containment inside = Containment.of(sketch(LongStream.rangeClosed(1, 4_000)), customers);
        assertTrue(inside.fraction() > 0.97, "subset: " + inside);
        assertTrue(inside.conclusive());
        Containment half = Containment.of(sketch(LongStream.rangeClosed(2_501, 7_500)), customers);
        assertEquals(0.5, half.fraction(), 0.05, "half overlaps: " + half);
        Containment none = Containment.of(sketch(LongStream.rangeClosed(10_001, 15_000)), customers);
        assertTrue(none.fraction() < 0.05, "disjoint: " + none);

        // Beyond the exact range (HLL mode): a million keys, 200,000 of them referred to.
        byte[] big = sketch(LongStream.rangeClosed(1, 1_000_000));
        Containment large = Containment.of(sketch(LongStream.rangeClosed(1, 200_000).map(n -> n * 5)), big);
        assertTrue(large.fraction() > 0.9, "large subset: " + large);
        // A few values next to a million cannot be judged from sketches.
        assertFalse(Containment.of(sketch(LongStream.rangeClosed(1, 50)), big).conclusive());
    }

    @Test
    void undeclaredCustomerIdIsAStrongProposal() {
        Column txn = col("urn:kilnmark:lab:billing.transactions", "customer_id", "int64", false, 100_000, 4_900);
        Column customers = col("urn:kilnmark:lab:crm.customers", "customer_id", "int64", true, 5_000, 5_000);
        sketches.put(txn.ref(), sketch(LongStream.rangeClosed(1, 4_900)));
        sketches.put(customers.ref(), sketch(LongStream.rangeClosed(1, 5_000)));
        List<Proposal> found = infer(List.of(txn, customers)).proposals();
        assertEquals(1, found.size(), found.toString());
        Proposal p = found.get(0);
        assertEquals(txn, p.from());
        assertEquals(customers, p.to());
        assertTrue(p.confidence() >= 0.95, p.toString());
        assertEquals("same name as the table's key", p.evidence().rule());
        assertTrue(p.evidence().containment() > 0.97);
        assertEquals("primary key", p.evidence().unique());
    }

    @Test
    void nameRules() {
        Column orders = col("urn:kilnmark:a:s.orders", "CustomerID", "int32", false, 10, 10);
        Column customer = col("urn:kilnmark:a:s.Customer", "ID", "int64", true, 10, 10);
        Column prefixed = col("urn:kilnmark:a:s.refunds", "billing_customer_id", "int64", false, 10, 10);
        Column bareId = col("urn:kilnmark:a:s.things", "id", "int64", true, 10, 10);
        for (Column c : List.of(orders, customer, prefixed, bareId)) {
            sketches.put(c.ref(), sketch(LongStream.rangeClosed(1, 10)));
        }
        List<Proposal> found = infer(List.of(orders, customer, prefixed, bareId)).proposals();
        assertEquals(Set.of("s.orders.CustomerID>s.Customer.ID", "s.refunds.billing_customer_id>s.Customer.ID"),
            Set.copyOf(found.stream().map(p -> p.from().assetName() + "." + p.from().field() + ">"
                + p.to().assetName() + "." + p.to().field()).toList()), "a bare id refers to nothing");
        Proposal plain = found.stream().filter(p -> p.from() == orders).findFirst().orElseThrow();
        Proposal withPrefix = found.stream().filter(p -> p.from() == prefixed).findFirst().orElseThrow();
        assertEquals("table name and id", plain.evidence().rule());
        assertEquals("table name and id, with a prefix", withPrefix.evidence().rule());
        assertTrue(plain.confidence() > withPrefix.confidence());
    }

    @Test
    void valuesOutsideTheKeyTypesThatDifferAndKeysThatAreNotUniqueSayNo() {
        Column customers = col("urn:kilnmark:a:s.customers", "id", "int64", true, 5_000, 5_000);
        sketches.put(customers.ref(), sketch(LongStream.rangeClosed(1, 5_000)));

        Column elsewhere = col("urn:kilnmark:a:s.orders", "customer_id", "int64", false, 900, 900);
        sketches.put(elsewhere.ref(), sketch(LongStream.rangeClosed(90_001, 90_900)));
        assertTrue(infer(List.of(elsewhere, customers)).proposals().isEmpty(), "disjoint values");

        Column asText = col("urn:kilnmark:a:s.notes", "customer_id", "text", false, 900, 900);
        sketches.put(asText.ref(), textSketch(List.of("1", "2", "3")));
        assertTrue(infer(List.of(asText, customers)).proposals().isEmpty(), "text against integers");

        Column notUnique = col("urn:kilnmark:a:s.visits", "customer_id", "int64", false, 5_000, 1_200);
        Column refers = col("urn:kilnmark:a:s.v", "visit_customer_id", "int64", false, 10, 10);
        assertFalse(KeyInference.keyLike(notUnique));
        assertTrue(KeyInference.keyLike(col("urn:kilnmark:a:s.copy", "customer_id", "int64", false, 3_000, 3_000)));
        assertTrue(infer(List.of(refers, notUnique)).proposals().isEmpty());
    }

    @Test
    void namesWithoutSketchesAreNeverStrong() {
        Column orders = col("urn:kilnmark:a:s.orders", "customer_id", "int64", false, 10, 10);
        Column customers = col("urn:kilnmark:a:s.customers", "id", "int64", true, 10, 10);
        assertTrue(infer(List.of(orders, customers)).proposals().isEmpty());
        var weak = new KeyInference(new KeyInference.Limits(100, 3, 0)).infer(List.of(orders, customers), c -> true,
            refs -> Map.of()).proposals();
        assertEquals(1, weak.size());
        assertTrue(weak.get(0).confidence() <= 0.5);
        assertNull(weak.get(0).evidence().containment());
    }

    @Test
    void aHandfulOfValuesCountsForLess() {
        Column orders = col("urn:kilnmark:a:s.orders", "customer_id", "int64", false, 3, 3);
        Column customers = col("urn:kilnmark:a:s.customers", "id", "int64", true, 10, 10);
        sketches.put(orders.ref(), sketch(LongStream.rangeClosed(1, 3)));
        sketches.put(customers.ref(), sketch(LongStream.rangeClosed(1, 10)));
        var p = new KeyInference(new KeyInference.Limits(100, 3, 0)).infer(List.of(orders, customers), c -> true,
            refs -> sketches).proposals();
        assertTrue(p.get(0).confidence() < 0.75, p.toString());
    }

    @Test
    void copiesOfOneKeyAreProposedOneWayOnly() {
        Column customers = col("urn:kilnmark:a:crm.customers", "customer_id", "int64", true, 5_000, 5_000);
        Column archive = col("urn:kilnmark:a:crm.customers_archive_2023", "customer_id", "int64", false, 3_000, 3_000);
        sketches.put(customers.ref(), sketch(LongStream.rangeClosed(1, 5_000)));
        sketches.put(archive.ref(), sketch(LongStream.rangeClosed(1, 3_000)));
        List<Proposal> found = infer(List.of(customers, archive)).proposals();
        assertEquals(1, found.size(), found.toString());
        assertEquals(archive, found.get(0).from());

        Column twin = col("urn:kilnmark:a:crm.customer", "customer_id", "int64", true, 5_000, 5_000);
        sketches.put(twin.ref(), sketch(LongStream.rangeClosed(1, 5_000)));
        assertEquals(1, infer(List.of(customers, twin)).proposals().size(), "two identical keys: one direction");
    }

    @Test
    void workIsBoundedAndScoped() {
        Column customers = col("urn:kilnmark:a:s.customers", "customer_id", "int64", true, 100, 100);
        sketches.put(customers.ref(), sketch(LongStream.rangeClosed(1, 100)));
        List<Column> all = new java.util.ArrayList<>(List.of(customers));
        for (int i = 0; i < 20; i++) {
            Column c = col("urn:kilnmark:b:s.t" + i, "customer_id", "int64", false, 1_000, 100);
            sketches.put(c.ref(), sketch(LongStream.rangeClosed(1, 100)));
            all.add(c);
        }
        AtomicReference<Set<ColumnRef>> asked = new AtomicReference<>();
        var r = new KeyInference(new KeyInference.Limits(5, 3, 0.75)).infer(all, c -> true, refs -> {
            asked.set(refs);
            return sketches;
        });
        assertEquals(5, r.pairsScored());
        assertEquals(15, r.pairsSkipped());
        assertEquals(5, r.proposals().size());
        assertEquals(6, asked.get().size(), "sketches are loaded only for the pairs scored");

        var scoped = infer(all.subList(0, 3));
        assertEquals(2, scoped.proposals().size());
        var onlyB = new KeyInference(KeyInference.Limits.DEFAULT).infer(all, c -> c.assetUrn().contains(":s.t3"),
            refs -> sketches);
        assertEquals(1, onlyB.proposals().size(), "only pairs touching the scanned source");

        // At most perColumn proposals for one referring column.
        List<Column> many = new java.util.ArrayList<>();
        Column from = col("urn:kilnmark:c:s.x", "customer_id", "int64", false, 100, 100);
        many.add(from);
        sketches.put(from.ref(), sketch(LongStream.rangeClosed(1, 100)));
        for (int i = 0; i < 6; i++) {
            Column t = col("urn:kilnmark:c:s.customers" + i, "customer_id", "int64", true, 100, 100);
            sketches.put(t.ref(), sketch(LongStream.rangeClosed(1, 100)));
            many.add(t);
        }
        long fromX = infer(many).proposals().stream().filter(p -> p.from() == from).count();
        assertEquals(3, fromX);
    }
}

package io.kilnmark.server.pipelines;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;

import io.kilnmark.kiln.MetadataEvent;
import io.kilnmark.kiln.Step;
import io.kilnmark.kiln.StepContext;
import io.kilnmark.kiln.StepException;
import io.kilnmark.kiln.StepSpec;
import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.kiln.connect.SourceConfig;

/**
 * A test connector that reads synthetic people (id, name) and writes by counting rows, so the
 * pipeline flow can run end to end without a database to write to. Listed in this test tree's
 * META-INF/services, so tests only. Its reader insists on the {@code token} setting it was built
 * with, which proves the server hands readers their own settings whatever the runner does.
 */
public final class MemoryConnector implements Connector {

    public static final String KIND = "memory-test";
    static final Schema PEOPLE = new Schema(List.of(Field.nullable("id", new ArrowType.Int(64, true)),
        Field.nullable("name", ArrowType.Utf8.INSTANCE)));
    /** Rows written, by target asset URN. */
    static final Map<String, AtomicLong> WRITTEN = new ConcurrentHashMap<>();

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String displayName() {
        return "Memory (test)";
    }

    @Override
    public List<Setting> settings() {
        return List.of(new Setting("rows", "Rows", false, "How many people to read", false),
            new Setting("host", "Host", false, "A host it pretends to reach", false));
    }

    @Override
    public List<String> validate(SourceConfig source) {
        return List.of();
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.DISCOVER, Capability.READ, Capability.WRITE);
    }

    @Override
    public List<String> hosts(SourceConfig source) {
        return source.setting("host") == null ? List.of() : List.of(source.setting("host"));
    }

    private static AssetInfo people(String path) {
        int dot = path.indexOf('.');
        return new AssetInfo(path, "table", dot > 0 ? path.substring(0, dot) : "", dot > 0 ? path.substring(dot + 1)
            : path, null, List.of(new AssetInfo.FieldInfo("id", 1, "bigint", "int64", true, null),
            new AssetInfo.FieldInfo("name", 2, "text", "text", true, null)), List.of(), false, true, Map.of());
    }

    @Override
    public List<AssetInfo> catalog(SourceConfig source) {
        return List.of(people("lab.people"));
    }

    @Override
    public AssetInfo asset(SourceConfig source, String path) throws ConnectorException {
        if (path.contains("missing")) {
            throw new ConnectorException("There is no " + path + " (password " + source.secret() + ")");
        }
        return people(path);
    }

    @Override
    public void test(SourceConfig source) {
        // always reachable
    }

    @Override
    public StepSpec reader(SourceConfig source, AssetInfo asset, ReadOptions read) {
        long rows = source.setting("rows") == null ? 2500 : Long.parseLong(source.setting("rows"));
        if (read.rowLimit() > 0) {
            rows = Math.min(rows, read.rowLimit());
        }
        String token = source.secret() == null ? "none" : source.secret();
        return new StepSpec("read-" + asset.path(), new People(rows, token), Map.of("asset-urn", read.assetUrn(),
            "token", token));
    }

    @Override
    public Step writer(SourceConfig source, WriteOptions write) throws ConnectorException {
        if (write.target().contains("refuse")) {
            throw new ConnectorException("Cannot write there with password " + source.secret());
        }
        return new Counter(write.assetUrn(), Boolean.TRUE.equals(write.options().get("fail")), source.secret());
    }

    /** Reads {@code rows} people in batches of 1,000. */
    static final class People implements Step {
        private final long rows;
        private final String token;
        private long sent;

        People(long rows, String token) {
            this.rows = rows;
            this.token = token;
        }

        @Override
        public String type() {
            return "memory-read";
        }

        @Override
        public void init(StepContext context) throws StepException {
            if (!token.equals(context.settings().get("token"))) {
                throw new StepException("The reader was not given its own settings");
            }
        }

        @Override
        public boolean process(StepContext context) throws StepException {
            int n = (int) Math.min(1000, rows - sent);
            if (n <= 0) {
                context.metadata().emit(new MetadataEvent(context.setting("asset-urn"), MetadataEvent.Kind.ROW_COUNT,
                    Map.of("rows", Long.toString(sent), "complete", "true"), Instant.now()));
                return false;
            }
            VectorSchemaRoot root = VectorSchemaRoot.create(PEOPLE, context.allocator());
            BigIntVector id = (BigIntVector) root.getVector(0);
            VarCharVector name = (VarCharVector) root.getVector(1);
            id.allocateNew(n);
            name.allocateNew(n);
            for (int i = 0; i < n; i++) {
                id.set(i, sent + i + 1);
                name.setSafe(i, ("Person " + (sent + i + 1)).getBytes(StandardCharsets.UTF_8));
            }
            root.setRowCount(n);
            sent += n;
            try {
                context.output().put(root);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StepException("interrupted", e);
            }
            return true;
        }
    }

    /** Counts what it is given; fails at the end when asked, naming the secret (which must be scrubbed). */
    static final class Counter implements Step {
        private final String urn;
        private final boolean fail;
        private final String secret;

        Counter(String urn, boolean fail, String secret) {
            this.urn = urn;
            this.fail = fail;
            this.secret = secret;
        }

        @Override
        public String type() {
            return "memory-write";
        }

        @Override
        public void init(StepContext context) {
            WRITTEN.put(urn, new AtomicLong());
        }

        @Override
        public boolean process(StepContext context) throws StepException {
            VectorSchemaRoot b;
            try {
                b = context.input().take(context.allocator());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StepException("interrupted", e);
            }
            if (b == null) {
                if (fail) {
                    throw new StepException("The target refused the rows; password " + secret);
                }
                return false;
            }
            WRITTEN.get(urn).addAndGet(b.getRowCount());
            b.close();
            return true;
        }
    }
}

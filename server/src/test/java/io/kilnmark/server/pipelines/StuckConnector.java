package io.kilnmark.server.pipelines;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import io.kilnmark.kiln.Step;
import io.kilnmark.kiln.StepContext;
import io.kilnmark.kiln.StepSpec;
import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.SourceConfig;

/**
 * A test connector whose reader is stuck where an interrupt cannot reach it (a blocking call that
 * ignores interrupts) until {@link #release} is counted down: for the preview's hard time limit.
 * Used directly through {@link FakeSources}, not listed with the services.
 */
final class StuckConnector implements Connector {

    static final String KIND = "test-stuck";
    /** Counted down to let every stuck reader go. */
    static volatile CountDownLatch release = new CountDownLatch(1);

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String displayName() {
        return "Stuck (test)";
    }

    @Override
    public List<Setting> settings() {
        return List.of();
    }

    @Override
    public List<String> validate(SourceConfig source) {
        return List.of();
    }

    @Override
    public List<AssetInfo> catalog(SourceConfig source) {
        return List.of(asset(source, "lab.stuck"));
    }

    @Override
    public AssetInfo asset(SourceConfig source, String path) {
        return new AssetInfo(path, "table", "lab", path, null, List.of(new AssetInfo.FieldInfo("id", 1, "bigint",
            "int64", true, null)), List.of(), false, true, Map.of());
    }

    @Override
    public StepSpec reader(SourceConfig source, AssetInfo asset, ReadOptions read) {
        return new StepSpec("stuck", new Step() {
            @Override
            public String type() {
                return "stuck-read";
            }

            @Override
            public void init(StepContext context) {
            }

            @Override
            public boolean process(StepContext context) {
                CountDownLatch until = release;
                while (true) {
                    try {
                        until.await();
                        return false;
                    } catch (InterruptedException ignored) {
                        // stuck: interrupts do not reach it
                    }
                }
            }
        }, Map.of());
    }
}

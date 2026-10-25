package io.kilnmark.server.pipelines;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.kilnmark.kiln.SettingSpec;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.SourceConfig;

/**
 * ADAPTER, to be replaced by direct calls once round 3's connector writers (stream c) are merged:
 * the parts of the writers' contract (docs/design/pipelines.md, section 5, "What a write reports")
 * that kiln-core on this branch does not have yet. Until then the methods are found by name, and
 * when a connector does not have them the server behaves as the contract's defaults say. Nothing
 * else in the server names these methods.
 *
 * <ul>
 *   <li>{@code Connector.writeOptions()}: the write option keys a connector's writer takes, as
 *       {@link SettingSpec}s. The write step allows exactly those in {@code options}, plus its own
 *       settings.</li>
 *   <li>{@code Connector.writtenAssetPath(SourceConfig, WriteOptions)}: the asset path a write is
 *       recorded under (for an S3 source with a prefix, prefix + path), so the written asset's URN
 *       is what a scan of the source records.</li>
 *   <li>{@link #PROFILE_SETTINGS} and {@link #PROFILE_ERROR}: how the source's profiling settings
 *       reach the writer, and how a writer says it could not profile what it wrote.</li>
 * </ul>
 */
final class WriterContract {

    /**
     * {@code WriteOptions.options} key holding the source's profiling settings for the writer's
     * profile, as text by the profile step's own names ({@code keep-values}, {@code examples},
     * {@code sample-size}, {@code sample-mode}, {@code sample-key}, {@code sample-seed},
     * {@code sample-max-bytes}, {@code sketch-fields}). Set by the server, never from YAML.
     */
    static final String PROFILE_SETTINGS = "profile_settings";

    /**
     * Attribute on an event for the written asset saying its profile could not be made, with why;
     * the write still counts.
     */
    static final String PROFILE_ERROR = "profile_error";

    private WriterContract() {
    }

    /** The write options the connector's writer declares; empty when it declares none (or cannot yet). */
    static List<SettingSpec> writeOptions(Connector connector) {
        Object v = call(connector, "writeOptions", new Class<?>[0]);
        List<SettingSpec> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof SettingSpec s) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /** The path the written asset is recorded under: the connector's answer, else the target as written. */
    static String writtenAssetPath(Connector connector, SourceConfig source, Connector.WriteOptions write) {
        Object v = call(connector, "writtenAssetPath", new Class<?>[] {SourceConfig.class, Connector.WriteOptions.class},
            source, write);
        return v instanceof String s && !s.isBlank() ? s : write.target();
    }

    private static Object call(Connector connector, String name, Class<?>[] types, Object... args) {
        if (connector == null) {
            return null;
        }
        try {
            Method m = connector.getClass().getMethod(name, types);
            return m.invoke(connector, args);
        } catch (NoSuchMethodException e) {
            return null; // a kiln-core without the writers' contract
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}

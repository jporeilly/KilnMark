package io.kilnmark.server;

import java.util.HashMap;
import java.util.Map;

import io.kilnmark.kiln.connect.ConnectorContext;
import io.kilnmark.kiln.connect.Connectors;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/**
 * Every connector on the class path; adding a connector jar adds a kind of source. Connectors get
 * the server's settings for them (allowed folders, credential policy) through a
 * {@link ConnectorContext}; people adding sources cannot change these.
 */
@ApplicationScoped
public class ConnectorsProducer {

    @Produces
    @ApplicationScoped
    Connectors connectors(KilnmarkConfig config) {
        return Connectors.load(context(config));
    }

    static ConnectorContext context(KilnmarkConfig config) {
        Map<String, String> s = new HashMap<>();
        config.files().roots().ifPresent(r -> s.put("kilnmark.files.roots", String.join(",", r)));
        s.put("kilnmark.files.parquet-threads", Integer.toString(config.files().parquetThreads()));
        s.put("kilnmark.files.parquet-chunk-mb", Integer.toString(config.files().parquetChunkMb()));
        config.files().nativeDir().ifPresent(d -> s.put("kilnmark.files.native-dir", d));
        s.put("kilnmark.s3.allow-default-credentials", Boolean.toString(config.s3().allowDefaultCredentials()));
        KilnmarkConfig.Documents d = config.documents();
        s.put("kilnmark.documents.max-file-mb", Integer.toString(d.maxFileMb()));
        s.put("kilnmark.documents.max-legacy-file-mb", Integer.toString(d.maxLegacyFileMb()));
        s.put("kilnmark.documents.max-characters", Integer.toString(d.maxCharacters()));
        s.put("kilnmark.documents.timeout-seconds", Integer.toString(d.timeoutSeconds()));
        s.put("kilnmark.documents.max-depth", Integer.toString(d.maxDepth()));
        s.put("kilnmark.documents.threads", Integer.toString(d.threads()));
        s.put("kilnmark.documents.chunk-characters", Integer.toString(d.chunkCharacters()));
        s.put("kilnmark.documents.chunk-overlap", Integer.toString(d.chunkOverlap()));
        return new ConnectorContext(s);
    }
}

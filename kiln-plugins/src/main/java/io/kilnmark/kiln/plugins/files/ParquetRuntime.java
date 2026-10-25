package io.kilnmark.kiln.plugins.files;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Set;

import dev.hardwood.HardwoodContext;
import dev.hardwood.InputFile;
import dev.hardwood.metadata.ColumnChunk;
import dev.hardwood.metadata.CompressionCodec;
import dev.hardwood.metadata.FileMetaData;
import dev.hardwood.metadata.RowGroup;
import dev.hardwood.reader.ParquetFileReader;

import io.kilnmark.kiln.connect.ConnectorContext;

/**
 * The one Hardwood context every Parquet read in this server shares, and the JVM-wide settings
 * Hardwood and the codecs read. One bounded pool of decoding threads serves every read, rather
 * than a pool per file.
 *
 * <p>Server settings ({@link ConnectorContext}):
 * <ul>
 *   <li>{@code kilnmark.files.parquet-threads}: decoding threads shared by all reads (default 2);</li>
 *   <li>{@code kilnmark.files.parquet-chunk-mb}: the most Hardwood fetches from a file in one
 *       request (default 8 MiB; Hardwood's own default is 128 MiB);</li>
 *   <li>{@code kilnmark.files.native-dir}: where snappy-java unpacks its native library (default
 *       {@code ~/.kilnmark/native}, readable by the server's user only).</li>
 * </ul>
 * Hardwood's optional use of the system's libdeflate is off unless {@code hardwood.uselibdeflate}
 * is set to true on the command line. System properties set on the command line always win.
 */
final class ParquetRuntime {

    static final String THREADS = "kilnmark.files.parquet-threads";
    static final String CHUNK_MB = "kilnmark.files.parquet-chunk-mb";
    static final String NATIVE_DIR = "kilnmark.files.native-dir";

    private static ConnectorContext settings = ConnectorContext.EMPTY;
    private static HardwoodContext context;

    private ParquetRuntime() {
    }

    /** Takes the server's settings; only the first call before any read counts. */
    static synchronized void configure(ConnectorContext serverSettings) {
        if (context == null && serverSettings != null) {
            settings = serverSettings;
        }
    }

    /** The shared context, created on first use with the settings given so far. */
    static synchronized HardwoodContext context() {
        if (context == null) {
            int chunk = Math.max(1, settings.integer(CHUNK_MB, 8));
            setIfAbsent("hardwood.internal.sequentialChunkSize", Integer.toString(chunk << 20));
            setIfAbsent("hardwood.internal.maxCoalescedBytes", Integer.toString(chunk << 20));
            setIfAbsent("hardwood.uselibdeflate", "false");
            ensureNativeDirectory();
            context = HardwoodContext.create(Math.max(1, settings.integer(THREADS, 2)));
        }
        return context;
    }

    /**
     * Points snappy-java at the private folder for its native library ({@value #NATIVE_DIR}),
     * before Snappy is first used to read or write.
     */
    static synchronized void ensureNativeDirectory() {
        if (System.getProperty("org.xerial.snappy.tempdir") == null) {
            String dir = settings.setting(NATIVE_DIR);
            Path native_ = dir != null ? Path.of(dir) : Path.of(System.getProperty("user.home"), ".kilnmark", "native");
            try {
                createPrivate(native_);
                System.setProperty("org.xerial.snappy.tempdir", native_.toString());
            } catch (IOException | RuntimeException e) {
                // snappy-java falls back to java.io.tmpdir
            }
        }
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }

    private static void createPrivate(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            return;
        }
        try {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException e) {
            Files.createDirectories(dir); // not a POSIX file system
        }
    }

    /** Opens a Parquet file on the shared context. */
    static ParquetFileReader open(InputFile file) throws IOException {
        return ParquetFileReader.open(file, context());
    }

    /** The codecs this server can decompress: those whose libraries are on the class path. */
    static Set<CompressionCodec> supportedCodecs() {
        Set<CompressionCodec> out = EnumSet.of(CompressionCodec.UNCOMPRESSED, CompressionCodec.GZIP);
        if (present("org.xerial.snappy.Snappy")) {
            out.add(CompressionCodec.SNAPPY);
        }
        if (present("com.github.luben.zstd.Zstd")) {
            out.add(CompressionCodec.ZSTD);
        }
        if (present("net.jpountz.lz4.LZ4Factory")) {
            out.add(CompressionCodec.LZ4);
            out.add(CompressionCodec.LZ4_RAW);
        }
        return out;
    }

    private static boolean present(String className) {
        try {
            Class.forName(className, false, ParquetRuntime.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /**
     * Problems with a file's codecs, or null when every column can be decompressed here.
     */
    static String unsupportedCodecs(FileMetaData md) {
        Set<CompressionCodec> supported = supportedCodecs();
        Set<CompressionCodec> missing = EnumSet.noneOf(CompressionCodec.class);
        for (RowGroup g : md.rowGroups()) {
            for (ColumnChunk c : g.columns()) {
                if (c.metaData() != null && c.metaData().codec() != null && !supported.contains(c.metaData().codec())) {
                    missing.add(c.metaData().codec());
                }
            }
        }
        if (missing.isEmpty()) {
            return null;
        }
        return "it is compressed with " + missing + ", which this server cannot decompress "
            + "(it reads " + supported + "); write the file with Snappy or ZSTD";
    }
}

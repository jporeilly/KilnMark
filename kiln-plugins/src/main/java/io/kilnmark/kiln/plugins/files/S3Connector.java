package io.kilnmark.kiln.plugins.files;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.ConnectorContext;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.plugins.documents.DocumentRuntime;

/**
 * A bucket in Amazon S3 or an S3-compatible store (VersityGW, MinIO, Ceph, ...). Settings
 * {@code endpoint} (empty for AWS), {@code region}, {@code bucket}, {@code prefix},
 * {@code access_key_id} and {@code path_style}; the source's secret is the secret access key.
 * Asset paths are object keys.
 *
 * <p>A source needs its own access key. Only when the server's administrator sets
 * {@code kilnmark.s3.allow-default-credentials=true} may a source on AWS (no endpoint) leave it
 * out and use the server's own AWS identity (environment, profile, instance role); never a source
 * with another endpoint, which would receive requests signed with that identity. Endpoints holding
 * a user name or password are refused.
 */
public final class S3Connector extends FileSourceConnector {

    /** The server setting that lets sources on AWS use the server's own credentials. */
    public static final String ALLOW_DEFAULT_CREDENTIALS = "kilnmark.s3.allow-default-credentials";

    static final String NO_DEFAULT_CREDENTIALS = "access_key_id: give the source's own access key ID, and its "
        + "secret access key as the source's secret; this server does not let sources use its own AWS credentials";

    private final boolean allowDefaultCredentials;

    /** The connector as the service loader creates it: every source needs its own access key. */
    public S3Connector() {
        this(false);
    }

    private S3Connector(boolean allowDefaultCredentials) {
        this.allowDefaultCredentials = allowDefaultCredentials;
    }

    @Override
    public Connector withContext(ConnectorContext context) {
        ParquetRuntime.configure(context);
        DocumentRuntime.configure(context);
        return new S3Connector(context.flag(ALLOW_DEFAULT_CREDENTIALS, false));
    }

    @Override
    public String kind() {
        return "s3";
    }

    @Override
    public String displayName() {
        return "S3 object storage";
    }

    @Override
    public List<Setting> settings() {
        return List.of(
            new Setting("endpoint", "Endpoint", false,
                "Leave empty for AWS. For S3-compatible storage, its URL, for example http://storage.example.com:9000",
                false),
            new Setting("region", "Region", false, "For example eu-west-2; default us-east-1", false),
            new Setting("bucket", "Bucket", true, "The bucket to scan, for example lab-raw", false),
            new Setting("prefix", "Prefix", false, "Only objects whose keys start with this, for example raw/", false),
            new Setting("access_key_id", "Access key ID", !allowDefaultCredentials,
                "The secret access key goes in the source's secret"
                    + (allowDefaultCredentials ? ". On AWS, leave empty to use the server's own AWS credentials" : ""),
                false),
            new Setting("path_style", "Path-style addressing", false,
                "true for most S3-compatible storage (VersityGW, MinIO); false (the default) for AWS", false),
            ENCODING,
            KEEP_TEXT,
            READ_TEXT_ON_DEMAND);
    }

    @Override
    public List<String> validate(SourceConfig source) {
        List<String> problems = new ArrayList<>();
        String bucket = source.setting("bucket");
        if (bucket == null) {
            problems.add("bucket: the name of the bucket to scan");
        } else if (bucket.contains("/") || bucket.contains(" ")) {
            problems.add("bucket: a bucket name only, such as lab-raw; put any folder in prefix");
        }
        String endpoint = source.setting("endpoint");
        if (endpoint != null) {
            try {
                URI u = new URI(endpoint);
                if (u.getRawUserInfo() != null || endpoint.contains("@")) {
                    problems.add("endpoint: must not hold a user name or password; put the access key ID in "
                        + "access_key_id and the secret access key in the source's secret");
                } else if (u.getHost() == null || !("http".equals(u.getScheme()) || "https".equals(u.getScheme()))) {
                    problems.add("endpoint: a URL such as https://storage.example.com:9000, not '" + endpoint + "'");
                }
            } catch (URISyntaxException e) {
                problems.add("endpoint: a URL such as https://storage.example.com:9000, not '" + endpoint + "'");
            }
        }
        String keyId = source.setting("access_key_id");
        if (keyId != null && (source.secret() == null || source.secret().isEmpty())) {
            problems.add("access_key_id: also give the secret access key, as the source's secret");
        }
        if (keyId == null && source.secret() != null && !source.secret().isEmpty()) {
            problems.add("access_key_id: give the access key ID that goes with the secret");
        } else if (keyId == null && !allowDefaultCredentials) {
            problems.add(NO_DEFAULT_CREDENTIALS);
        } else if (keyId == null && endpoint != null) {
            problems.add("access_key_id: required with an endpoint; the server's own AWS credentials are only used "
                + "for AWS itself");
        }
        checkBoolean(source, "path_style", problems);
        checkEncoding(source, problems);
        checkBoolean(source, "keep_text", problems);
        checkBoolean(source, "read_text_on_demand", problems);
        return problems;
    }

    @Override
    protected FileStore open(SourceConfig source) throws IOException {
        return new S3FileStore(settings(source), allowDefaultCredentials);
    }

    static S3FileStore.Settings settings(SourceConfig source) {
        return new S3FileStore.Settings(source.setting("endpoint"), source.setting("region"),
            source.setting("bucket"), source.setting("prefix"), source.setting("access_key_id"), source.secret(),
            Boolean.parseBoolean(source.setting("path_style")));
    }

    @Override
    protected String container(SourceConfig source, String path) {
        String folder = folder(path);
        return source.setting("bucket") + (folder.isEmpty() ? "" : "/" + folder);
    }
}

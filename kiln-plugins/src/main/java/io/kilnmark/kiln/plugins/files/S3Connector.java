package io.kilnmark.kiln.plugins.files;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import io.kilnmark.kiln.connect.SourceConfig;

/**
 * A bucket in Amazon S3 or an S3-compatible store (VersityGW, MinIO, Ceph, ...). Settings
 * {@code endpoint} (empty for AWS), {@code region}, {@code bucket}, {@code prefix},
 * {@code access_key_id} and {@code path_style}; the source's secret is the secret access key.
 * Without an access key ID the AWS default credentials (environment, profile, instance role) are
 * used. Asset paths are object keys.
 */
public final class S3Connector extends FileSourceConnector {

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
            new Setting("access_key_id", "Access key ID", false,
                "The secret access key goes in the source's secret. Leave empty to use the server's AWS credentials",
                false),
            new Setting("path_style", "Path-style addressing", false,
                "true for most S3-compatible storage (VersityGW, MinIO); false (the default) for AWS", false));
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
                if (u.getHost() == null || !("http".equals(u.getScheme()) || "https".equals(u.getScheme()))) {
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
        }
        checkBoolean(source, "path_style", problems);
        return problems;
    }

    @Override
    protected FileStore open(SourceConfig source) {
        return new S3FileStore(settings(source));
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

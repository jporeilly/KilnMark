package io.kilnmark.kiln.plugins.files;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dev.hardwood.InputFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Objects in an S3 bucket, on AWS or any S3-compatible store (VersityGW, MinIO, Ceph, ...).
 *
 * <p>Objects are streamed, never downloaded whole: a catalogue reads only the first bytes of text
 * files and the footer of Parquet files with ranged requests, and a read stops the transfer when
 * it stops early.
 */
public final class S3FileStore implements FileStore {

    /** How to reach a bucket. The secret is never logged; {@link #toString} hides it. */
    public record Settings(String endpoint, String region, String bucket, String prefix, String accessKeyId,
                           String secretAccessKey, boolean pathStyle) {

        @Override
        public String toString() {
            return "S3[endpoint=" + endpoint + ", region=" + region + ", bucket=" + bucket + ", prefix=" + prefix
                + ", accessKeyId=" + accessKeyId + ", secret=" + (secretAccessKey == null ? "none" : "hidden") + "]";
        }
    }

    static final String DEFAULT_REGION = "us-east-1";

    private final Settings settings;
    private final S3Client s3;
    private final Set<ResponseInputStream<GetObjectResponse>> active = ConcurrentHashMap.newKeySet();
    private volatile boolean cancelled;

    /**
     * @param allowDefaultCredentials whether a source without an access key may use the server's
     *                                own AWS credentials (environment, profile, instance role);
     *                                never with an endpoint other than AWS
     * @throws IOException when the settings would use credentials the server does not allow
     */
    public S3FileStore(Settings settings, boolean allowDefaultCredentials) throws IOException {
        this(settings, allowDefaultCredentials, null);
    }

    /**
     * @param callTimeout when not null, the longest any one request may take, connection included:
     *                    for checks, which must end in time
     */
    public S3FileStore(Settings settings, boolean allowDefaultCredentials, Duration callTimeout) throws IOException {
        this.settings = settings;
        if (settings.endpoint() != null && settings.endpoint().contains("@")) {
            throw new IOException("The endpoint must not hold a user name or password: put the access key ID in "
                + "access_key_id and the secret access key in the source's secret");
        }
        AwsCredentialsProvider credentials;
        if (settings.accessKeyId() != null) {
            credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(settings.accessKeyId(),
                settings.secretAccessKey() == null ? "" : settings.secretAccessKey()));
        } else if (allowDefaultCredentials && settings.endpoint() == null) {
            credentials = DefaultCredentialsProvider.builder().build();
        } else {
            throw new IOException(S3Connector.NO_DEFAULT_CREDENTIALS);
        }
        var builder = S3Client.builder()
            .region(Region.of(settings.region() == null ? DEFAULT_REGION : settings.region()))
            .credentialsProvider(credentials)
            .forcePathStyle(settings.pathStyle())
            .httpClientBuilder(UrlConnectionHttpClient.builder()
                .connectionTimeout(Duration.ofSeconds(10))
                .socketTimeout(Duration.ofSeconds(60)))
            // S3-compatible stores do not all support the newer default checksums
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
        if (settings.endpoint() != null) {
            builder.endpointOverride(URI.create(settings.endpoint()));
        }
        if (callTimeout != null) {
            builder.overrideConfiguration(o -> o.apiCallTimeout(callTimeout).apiCallAttemptTimeout(callTimeout));
        }
        this.s3 = builder.build();
    }

    @Override
    public List<Entry> list() throws IOException {
        return list(null);
    }

    /** Lists page by page (1,000 keys each), giving up between pages once {@code deadline} has passed. */
    @Override
    public List<Entry> list(java.time.Instant deadline) throws IOException {
        List<Entry> out = new ArrayList<>();
        ListObjectsV2Request.Builder req = ListObjectsV2Request.builder().bucket(settings.bucket());
        if (settings.prefix() != null) {
            req.prefix(settings.prefix());
        }
        try {
            for (S3Object o : s3.listObjectsV2Paginator(req.build()).contents()) {
                if (deadline != null && (java.time.Instant.now().isAfter(deadline)
                    || Thread.currentThread().isInterrupted())) {
                    throw new IOException("Listing the bucket took too long (" + out.size() + " objects so far): set "
                        + "prefix to a smaller part of the bucket, or type the folders to scan");
                }
                if (o.key().endsWith("/")) {
                    continue; // a folder marker
                }
                out.add(new Entry(o.key(), o.size() == null ? 0 : o.size(), o.lastModified()));
            }
        } catch (SdkException e) {
            throw explain(e, "list the bucket");
        }
        out.sort(java.util.Comparator.comparing(Entry::path));
        return out;
    }

    /** One object, by a HEAD request; only within the prefix, as the listing. */
    @Override
    public Entry entry(String key) throws IOException {
        if (settings.prefix() != null && !key.startsWith(settings.prefix()) || key.endsWith("/")) {
            throw new IOException("There is no object " + key + " in this source: it is outside its prefix");
        }
        try {
            var head = s3.headObject(software.amazon.awssdk.services.s3.model.HeadObjectRequest.builder()
                .bucket(settings.bucket()).key(key).build());
            return new Entry(key, head.contentLength() == null ? 0 : head.contentLength(), head.lastModified());
        } catch (SdkException e) {
            throw explain(e, "find " + describe(key));
        }
    }

    /** One listing request for at most one key: checks the endpoint, bucket, key and permission. */
    @Override
    public void probe() throws IOException {
        ListObjectsV2Request.Builder req = ListObjectsV2Request.builder().bucket(settings.bucket()).maxKeys(1);
        if (settings.prefix() != null) {
            req.prefix(settings.prefix());
        }
        try {
            s3.listObjectsV2(req.build());
        } catch (SdkException e) {
            throw explain(e, "list the bucket");
        }
    }

    @Override
    public InputStream open(String key) throws IOException {
        ResponseInputStream<GetObjectResponse> response = start(get(key).build(), key);
        return new FilterInputStream(response) {
            @Override
            public void close() throws IOException {
                finish(response); // stop the transfer rather than drain the rest of the object
            }
        };
    }

    /** Starts a GET, tracked so {@link #cancel} can stop it. */
    private ResponseInputStream<GetObjectResponse> start(GetObjectRequest req, String key) throws IOException {
        if (cancelled) {
            throw new IOException("The read of " + describe(key) + " was stopped");
        }
        try {
            ResponseInputStream<GetObjectResponse> response = s3.getObject(req);
            active.add(response);
            if (cancelled) {
                finish(response);
                throw new IOException("The read of " + describe(key) + " was stopped");
            }
            return response;
        } catch (SdkException e) {
            throw explain(e, "read " + describe(key));
        }
    }

    private void finish(ResponseInputStream<GetObjectResponse> response) {
        active.remove(response);
        response.abort();
        try {
            response.close();
        } catch (IOException | RuntimeException e) {
            // aborted: nothing left to release
        }
    }

    @Override
    public void cancel() {
        cancelled = true;
        for (ResponseInputStream<GetObjectResponse> r : active) {
            finish(r);
        }
    }

    @Override
    public byte[] head(String key, int bytes) throws IOException {
        return range(key, 0, bytes);
    }

    /** Up to {@code length} bytes from {@code offset}; fewer at the end of the object. */
    byte[] range(String key, long offset, int length) throws IOException {
        if (length <= 0) {
            return new byte[0];
        }
        ResponseInputStream<GetObjectResponse> response;
        try {
            response = start(get(key).range("bytes=" + offset + "-" + (offset + length - 1)).build(), key);
        } catch (IOException e) {
            if (e.getCause() instanceof S3Exception s3e && s3e.statusCode() == 416) {
                return new byte[0]; // an empty object, or a range past its end
            }
            throw e;
        }
        try {
            byte[] b = response.readNBytes(length);
            if (cancelled) {
                throw new IOException("The read of " + describe(key) + " was stopped"); // aborted: b is cut short
            }
            return b;
        } catch (IOException | RuntimeException e) {
            throw cancelled ? new IOException("The read of " + describe(key) + " was stopped", e)
                : new IOException("Reading " + describe(key) + " failed: " + e.getMessage(), e);
        } finally {
            finish(response);
        }
    }

    private GetObjectRequest.Builder get(String key) {
        return GetObjectRequest.builder().bucket(settings.bucket()).key(key);
    }

    @Override
    public InputFile parquet(String key, long size) {
        return new InputFile() {
            @Override
            public void open() {
                // each range is its own request
            }

            @Override
            public ByteBuffer readRange(long offset, int length) throws IOException {
                byte[] b = range(key, offset, length);
                if (b.length != length) {
                    throw new IOException("Read " + b.length + " of " + length + " bytes at " + offset + " from "
                        + describe(key) + ": the object changed or is shorter than listed; scan the source again");
                }
                return ByteBuffer.wrap(b);
            }

            @Override
            public long length() {
                return size;
            }

            @Override
            public String name() {
                return key;
            }

            @Override
            public void close() {
                // nothing held between requests
            }
        };
    }

    @Override
    public String describe(String key) {
        return "s3://" + settings.bucket() + "/" + key;
    }

    /** An exception whose message says what to change. */
    IOException explain(SdkException e, String doing) {
        String where = settings.endpoint() == null ? "AWS (" + (settings.region() == null ? DEFAULT_REGION
            : settings.region()) + ")" : settings.endpoint();
        String advice;
        if (e instanceof S3Exception s3e) {
            String code = s3e.awsErrorDetails() == null ? "" : s3e.awsErrorDetails().errorCode();
            advice = switch (code == null ? "" : code) {
                case "NoSuchBucket" -> "the bucket '" + settings.bucket() + "' does not exist at " + where
                    + ": check the bucket name, endpoint and region";
                case "NoSuchKey" -> "the object no longer exists: scan the source again to update the catalogue";
                case "InvalidAccessKeyId" -> "the access key ID is not known to " + where
                    + ": check access_key_id";
                case "SignatureDoesNotMatch" -> "the secret access key does not match the access key ID: "
                    + "check the source's secret";
                case "AccessDenied" -> "access denied: give the access key permission to list and read "
                    + "objects in the bucket '" + settings.bucket() + "'";
                case "PermanentRedirect", "AuthorizationHeaderMalformed" -> "the bucket is in another region: "
                    + "set region to the bucket's region";
                default -> s3e.statusCode() == 403 ? "access denied (" + code + "): check access_key_id, the secret "
                    + "and the key's permissions on the bucket" : (code.isEmpty() ? "" : code + ": ")
                    + s3e.getMessage();
            };
        } else if (e instanceof SdkClientException) {
            advice = "cannot reach " + where + " (" + e.getMessage() + "): check the endpoint and that the "
                + "server is running and reachable from this server";
        } else {
            advice = e.getMessage();
        }
        return new IOException("Cannot " + doing + ": " + advice, e);
    }

    @Override
    public void close() {
        cancel();
        s3.close();
    }
}

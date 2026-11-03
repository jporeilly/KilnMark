package io.kilnmark.kiln.plugins.files;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
import software.amazon.awssdk.services.s3.model.GetObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.Owner;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.Tag;

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

    /** AWS's own S3 host for a region, which a source without an endpoint reaches over HTTPS. */
    static String awsHost(String region) {
        return "s3." + region + ".amazonaws.com";
    }

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
        String region = settings.region() == null ? DEFAULT_REGION : settings.region();
        var builder = S3Client.builder()
            .region(Region.of(region))
            .credentialsProvider(credentials)
            .forcePathStyle(settings.pathStyle())
            .httpClientBuilder(UrlConnectionHttpClient.builder()
                .connectionTimeout(Duration.ofSeconds(10))
                .socketTimeout(Duration.ofSeconds(60)))
            // S3-compatible stores do not all support the newer default checksums
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
        // Always an endpoint of the source's own: without one the SDK would take AWS_ENDPOINT_URL, the
        // aws.endpointUrl property or a profile's endpoint_url from this server, which could be plain
        // HTTP or another host than the allow-list was told (ADR 0034).
        builder.endpointOverride(URI.create(settings.endpoint() != null ? settings.endpoint()
            : "https://" + awsHost(region)));
        if (callTimeout != null) {
            builder.overrideConfiguration(o -> o.apiCallTimeout(callTimeout).apiCallAttemptTimeout(callTimeout));
        }
        this.s3 = builder.build();
    }

    /** The endpoint the client sends every request to, for tests. */
    URI endpoint() {
        return s3.serviceClientConfiguration().endpointOverride().orElse(null);
    }

    @Override
    public List<Entry> list() throws IOException {
        return list(null);
    }

    /** Lists page by page (1,000 keys each), giving up between pages once {@code deadline} has passed. */
    @Override
    public List<Entry> list(java.time.Instant deadline) throws IOException {
        List<Entry> out = new ArrayList<>();
        ListObjectsV2Request.Builder req = ListObjectsV2Request.builder().bucket(settings.bucket()).fetchOwner(true);
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
                out.add(new Entry(o.key(), o.size() == null ? 0 : o.size(), o.lastModified(), owner(o.owner()), o.eTag()));
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
        // Keys are read only in plain form: an empty, "." or ".." part is a second spelling of a path
        // to the person reading it, and whatever is keyed by the path (the catalogue's marks) must not
        // be reached under another.
        for (String part : key.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new IOException("There is no object " + key + " to read here: keys with empty, '.' or '..' "
                    + "parts are not read");
            }
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

    private static String owner(Owner o) {
        if (o == null) {
            return null;
        }
        String name = o.displayName();
        if (name != null && !name.isBlank()) {
            return name;
        }
        return o.id() == null || o.id().isBlank() ? null : o.id();
    }

    @Override
    public Map<String, String> tags(String key) {
        if (cancelled) {
            return Map.of();
        }
        try {
            Map<String, String> out = new TreeMap<>();
            for (Tag t : s3.getObjectTagging(GetObjectTaggingRequest.builder().bucket(settings.bucket()).key(key)
                .build()).tagSet()) {
                if (t.key() != null) {
                    out.put(t.key(), t.value() == null ? "" : t.value());
                }
            }
            return out;
        } catch (SdkException | UnsupportedOperationException e) {
            return Map.of(); // no permission to read tags, or a store without tagging: not an error
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

    /** Bytes per uploaded part at first: S3's minimum is 5 MiB for every part but the last. */
    static final int PART_BYTES = 8 << 20;

    /**
     * The size of part {@code number} (1-based): 8 MiB for the first 1,000 parts, 32 MiB up to
     * 5,000, then 128 MiB, so an object stays within S3's 10,000 parts up to about 780 GB while
     * small files keep small buffers.
     */
    static int partSize(int number) {
        return number <= 1000 ? PART_BYTES : number <= 5000 ? 32 << 20 : 128 << 20;
    }

    /**
     * Writes an object: a file smaller than one part goes up in one PUT at the end; a larger one as a
     * multipart upload whose parts are sent as they fill and which is completed only at the end, so
     * nobody sees the object before it is whole. A failure or {@link Upload#abort()} aborts the
     * upload, and S3 discards the parts. Without {@code overwrite}, an existing object is refused
     * before anything is sent (a HEAD request; an object created by someone else during the upload
     * would still be replaced).
     */
    @Override
    public Upload create(String key, boolean overwrite) throws IOException {
        if (key == null || key.isBlank() || key.endsWith("/")
            || settings.prefix() != null && !key.startsWith(settings.prefix())) {
            throw new IOException("Refusing to write '" + key + "': it is outside this source's prefix");
        }
        if (cancelled) {
            throw new IOException("The write of " + describe(key) + " was stopped");
        }
        if (!overwrite) {
            try {
                s3.headObject(software.amazon.awssdk.services.s3.model.HeadObjectRequest.builder()
                    .bucket(settings.bucket()).key(key).build());
                throw new IOException(describe(key) + " already exists: set overwrite to true to replace it, or "
                    + "write to another path");
            } catch (S3Exception e) {
                if (e.statusCode() != 404) {
                    throw writeError(e, key);
                }
            } catch (SdkException e) {
                throw writeError(e, key);
            }
        }
        return new S3Upload(key);
    }

    /** {@link #explain}, with what writing needs when access is denied. */
    private IOException writeError(SdkException e, String key) {
        if (e instanceof S3Exception s3e && (s3e.statusCode() == 403 || s3e.awsErrorDetails() != null
            && "AccessDenied".equals(s3e.awsErrorDetails().errorCode()))) {
            return new IOException("Cannot write " + describe(key) + ": access denied: give the access key "
                + "permission to write objects in the bucket '" + settings.bucket() + "' (s3:PutObject and "
                + "s3:AbortMultipartUpload, and s3:GetObject to check whether the object exists)", e);
        }
        return explain(e, "write " + describe(key));
    }

    /** An object being uploaded. */
    private final class S3Upload implements Upload {

        private final String key;
        private byte[] part = new byte[PART_BYTES];
        private final List<software.amazon.awssdk.services.s3.model.CompletedPart> parts = new ArrayList<>();
        private int filled;
        private String uploadId;
        private boolean done;
        private final java.io.OutputStream stream = new java.io.OutputStream() {
            @Override
            public void write(int b) throws IOException {
                write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                while (len > 0) {
                    int n = Math.min(len, part.length - filled);
                    System.arraycopy(b, off, part, filled, n);
                    filled += n;
                    off += n;
                    len -= n;
                    if (filled == part.length) {
                        sendPart();
                    }
                }
            }
        };

        S3Upload(String key) {
            this.key = key;
        }

        /**
         * The filled part as a request body, read from the buffer itself rather than a copy (a
         * 128 MiB part would otherwise be held twice). The call that sends it returns before the
         * buffer is filled again; a retry rereads it from the start.
         */
        private software.amazon.awssdk.core.sync.RequestBody body() {
            return software.amazon.awssdk.core.sync.RequestBody.fromInputStream(
                new java.io.ByteArrayInputStream(part, 0, filled), filled);
        }

        @Override
        public java.io.OutputStream stream() {
            return stream;
        }

        private void sendPart() throws IOException {
            if (done || cancelled) {
                throw new IOException("The write of " + describe(key) + " was stopped");
            }
            try {
                if (uploadId == null) {
                    uploadId = s3.createMultipartUpload(software.amazon.awssdk.services.s3.model
                        .CreateMultipartUploadRequest.builder().bucket(settings.bucket()).key(key).build()).uploadId();
                }
                int number = parts.size() + 1;
                var response = s3.uploadPart(software.amazon.awssdk.services.s3.model.UploadPartRequest.builder()
                        .bucket(settings.bucket()).key(key).uploadId(uploadId).partNumber(number)
                        .contentLength((long) filled).build(),
                    body());
                parts.add(software.amazon.awssdk.services.s3.model.CompletedPart.builder().partNumber(number)
                    .eTag(response.eTag()).build());
                filled = 0;
                if (partSize(number + 1) != part.length) {
                    part = new byte[partSize(number + 1)];
                }
            } catch (SdkException e) {
                throw writeError(e, key);
            }
        }

        @Override
        public void commit() throws IOException {
            if (done || cancelled) {
                throw new IOException("The write of " + describe(key) + " was stopped");
            }
            try {
                if (uploadId == null) {
                    s3.putObject(software.amazon.awssdk.services.s3.model.PutObjectRequest.builder()
                            .bucket(settings.bucket()).key(key).contentLength((long) filled).build(),
                        body());
                } else {
                    if (filled > 0) {
                        sendPart();
                    }
                    s3.completeMultipartUpload(software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest
                        .builder().bucket(settings.bucket()).key(key).uploadId(uploadId)
                        .multipartUpload(software.amazon.awssdk.services.s3.model.CompletedMultipartUpload.builder()
                            .parts(parts).build()).build());
                }
                done = true;
            } catch (SdkException e) {
                throw writeError(e, key);
            }
        }

        @Override
        public void abort() {
            if (done) {
                return;
            }
            done = true;
            if (uploadId != null) {
                // a cancelled run interrupts this thread, and the HTTP client gives up at once on an
                // interrupted thread: abort with the flag cleared, then set it again
                boolean interrupted = Thread.interrupted();
                try {
                    s3.abortMultipartUpload(software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest
                        .builder().bucket(settings.bucket()).key(key).uploadId(uploadId).build());
                } catch (RuntimeException e) {
                    // the bucket's lifecycle rules remove abandoned uploads; nothing was made visible
                } finally {
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    @Override
    public String copyKey(String key) {
        return "s3\n" + settings.endpoint() + "\n" + settings.region() + "\n" + settings.accessKeyId() + "\n"
            + describe(key);
    }

    /** This source's objects: those under its prefix (keys are whole object keys). */
    @Override
    public String copyScope() {
        return copyKey(settings.prefix() == null ? "" : settings.prefix());
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

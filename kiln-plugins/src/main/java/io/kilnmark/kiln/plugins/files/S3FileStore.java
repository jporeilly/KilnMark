package io.kilnmark.kiln.plugins.files;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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

    public S3FileStore(Settings settings) {
        this.settings = settings;
        AwsCredentialsProvider credentials = settings.accessKeyId() == null
            ? DefaultCredentialsProvider.builder().build()
            : StaticCredentialsProvider.create(AwsBasicCredentials.create(settings.accessKeyId(),
                settings.secretAccessKey() == null ? "" : settings.secretAccessKey()));
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
        this.s3 = builder.build();
    }

    @Override
    public List<Entry> list() throws IOException {
        List<Entry> out = new ArrayList<>();
        ListObjectsV2Request.Builder req = ListObjectsV2Request.builder().bucket(settings.bucket());
        if (settings.prefix() != null) {
            req.prefix(settings.prefix());
        }
        try {
            for (S3Object o : s3.listObjectsV2Paginator(req.build()).contents()) {
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

    @Override
    public InputStream open(String key) throws IOException {
        try {
            ResponseInputStream<GetObjectResponse> response = s3.getObject(get(key).build());
            return new FilterInputStream(response) {
                @Override
                public void close() throws IOException {
                    response.abort(); // stop the transfer rather than drain the rest of the object
                    super.close();
                }
            };
        } catch (SdkException e) {
            throw explain(e, "read " + describe(key));
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
        try {
            return s3.getObjectAsBytes(get(key).range("bytes=" + offset + "-" + (offset + length - 1)).build())
                .asByteArray();
        } catch (S3Exception e) {
            if (e.statusCode() == 416) {
                return new byte[0]; // an empty object, or a range past its end
            }
            throw explain(e, "read " + describe(key));
        } catch (SdkException e) {
            throw explain(e, "read " + describe(key));
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
        s3.close();
    }
}

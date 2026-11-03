package io.kilnmark.kiln.plugins.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.connect.SourceConfig;

/**
 * The hosts an S3 source reports for the allowed-hosts list are the hosts its requests really go to,
 * as the AWS SDK works them out (ADR 0035). Nothing is sent: each request is stopped just before.
 */
class S3HostsTest {

    private static List<String> hosts(String... keyValues) {
        Map<String, String> settings = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            settings.put(keyValues[i], keyValues[i + 1]);
        }
        return new S3Connector().hosts(new SourceConfig("s", "s3", settings, null));
    }

    private static List<String> withProperty(String name, String value, String... keyValues) {
        String before = System.getProperty(name);
        System.setProperty(name, value);
        try {
            return hosts(keyValues);
        } finally {
            if (before == null) {
                System.clearProperty(name);
            } else {
                System.setProperty(name, before);
            }
        }
    }

    /** With an endpoint, the bucket is part of the host unless path_style is on. */
    @Test
    void theBucketIsInTheHostUnlessPathStyle() {
        assertEquals(List.of("mybucket.storage.example.com"),
            hosts("bucket", "mybucket", "endpoint", "https://storage.example.com:9000"));
        assertEquals(List.of("storage.example.com"),
            hosts("bucket", "mybucket", "endpoint", "https://storage.example.com:9000", "path_style", "true"));
    }

    /** On AWS, whatever host the SDK's rules choose: the bucket's, FIPS, other partitions, access points. */
    @Test
    void awsHostsAreTheOnesRequestsReach() {
        assertEquals(List.of("mybucket.s3.eu-west-2.amazonaws.com"), hosts("bucket", "mybucket", "region", "eu-west-2"));
        assertEquals(List.of("mybucket.s3.amazonaws.com"), hosts("bucket", "mybucket"), "us-east-1 uses AWS's global host");
        assertEquals(List.of("s3.eu-west-2.amazonaws.com"), hosts("bucket", "my.dotted.bucket", "region", "eu-west-2"),
            "a bucket with dots goes in the path");
        assertEquals(List.of("mybucket.s3.cn-north-1.amazonaws.com.cn"), hosts("bucket", "mybucket", "region",
            "cn-north-1"));
        assertEquals(List.of("mybucket.s3-fips.us-gov-west-1.amazonaws.com"),
            withProperty("aws.useFipsEndpoint", "true", "bucket", "mybucket", "region", "us-gov-west-1"));
        String ap = hosts("bucket", "arn:aws:s3:eu-west-2:123456789012:accesspoint:myap", "region", "eu-west-2")
            .getFirst();
        assertTrue(ap.contains("s3-accesspoint"), ap);
    }

    /** An endpoint set on this server is ignored (ADR 0034), so it is not what the list sees either. */
    @Test
    void anEndpointFromTheServerIsNotReported() {
        assertEquals(List.of("mybucket.s3.eu-west-2.amazonaws.com"),
            withProperty("aws.endpointUrl", "http://127.0.0.1:9", "bucket", "mybucket", "region", "eu-west-2"));
    }

    /** What the list is told is where a real read goes: the same client, caught just before sending. */
    @Test
    void theReportedHostIsWhereAReadGoes() throws Exception {
        for (S3FileStore.Settings s : List.of(
            new S3FileStore.Settings("https://storage.example.com:9000", null, "mybucket", "in/", "k", "s", false),
            new S3FileStore.Settings("https://storage.example.com:9000", null, "mybucket", null, "k", "s", true),
            new S3FileStore.Settings(null, "eu-west-2", "mybucket", "landing/", "k", "s", false),
            new S3FileStore.Settings(null, "cn-north-1", "my.dotted.bucket", null, "k", "s", false))) {
            java.util.concurrent.atomic.AtomicReference<java.net.URI> sent = new java.util.concurrent.atomic.AtomicReference<>();
            software.amazon.awssdk.core.interceptor.ExecutionInterceptor stop =
                new software.amazon.awssdk.core.interceptor.ExecutionInterceptor() {
                    @Override
                    public void beforeTransmission(software.amazon.awssdk.core.interceptor.Context.BeforeTransmission c,
                                                   software.amazon.awssdk.core.interceptor.ExecutionAttributes a) {
                        sent.compareAndSet(null, c.httpRequest().getUri());
                        throw new IllegalStateException("stopped before sending");
                    }
                };
            try (S3FileStore store = new S3FileStore(s, false, null, stop)) {
                assertThrows(Exception.class, store::list);
            }
            assertEquals(sent.get().getHost(), S3FileStore.hostReached(s), s.toString());
        }
    }

    /** Settings the SDK refuses give no host, and the list refuses the source rather than guess. */
    @Test
    void settingsTheSdkRefusesAreNotGuessed() {
        assertThrows(IllegalArgumentException.class, () -> hosts("bucket", "mybucket", "region", "eu-west-2.evil.com"));
    }
}

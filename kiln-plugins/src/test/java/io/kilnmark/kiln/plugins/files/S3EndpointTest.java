package io.kilnmark.kiln.plugins.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;

/**
 * Where an S3 source's requests really go, whatever this server's environment says (ADR 0034): each
 * request is caught just before it is sent, and stopped, so nothing leaves this machine.
 */
class S3EndpointTest {

    /** Records the address of the first request and stops it there. */
    private static final class Catch implements ExecutionInterceptor {
        final AtomicReference<URI> sent = new AtomicReference<>();

        @Override
        public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attributes) {
            sent.compareAndSet(null, context.httpRequest().getUri());
            throw new IllegalStateException("stopped before sending");
        }
    }

    private static URI firstRequest(String endpoint, String region) throws IOException {
        Catch c = new Catch();
        try (S3FileStore s3 = new S3FileStore(new S3FileStore.Settings(endpoint, region, "mybucket", null, "key",
            "secret", false), false, null, c)) {
            assertThrows(Exception.class, s3::list);
        }
        return c.sent.get();
    }

    private static URI withProperty(String name, String value, String endpoint, String region) throws IOException {
        String before = System.getProperty(name);
        System.setProperty(name, value);
        try {
            return firstRequest(endpoint, region);
        } finally {
            if (before == null) {
                System.clearProperty(name);
            } else {
                System.setProperty(name, before);
            }
        }
    }

    /** An endpoint this server's environment names (here aws.endpointUrl) is never used. */
    @Test
    void withoutAnEndpointRequestsGoToAwsOverHttpsWhateverTheServerSays() throws Exception {
        URI sent = withProperty("aws.endpointUrl", "http://127.0.0.1:9", null, "eu-west-2");
        assertEquals("https", sent.getScheme());
        assertEquals("mybucket.s3.eu-west-2.amazonaws.com", sent.getHost());
    }

    /** AWS's own endpoints are still worked out as usual: FIPS, set for the whole server, still works. */
    @Test
    void awssOwnEndpointsStillWork() throws Exception {
        URI fips = withProperty("aws.useFipsEndpoint", "true", null, "us-gov-west-1");
        assertEquals("https", fips.getScheme());
        assertEquals("mybucket.s3-fips.us-gov-west-1.amazonaws.com", fips.getHost());
        assertEquals("mybucket.s3.cn-north-1.amazonaws.com.cn", firstRequest(null, "cn-north-1").getHost());
    }

    /** A source's own endpoint is used as given. */
    @Test
    void aSourcesEndpointIsUsed() throws Exception {
        URI sent = withProperty("aws.endpointUrl", "http://127.0.0.1:9", "https://storage.example.com:9000", null);
        assertEquals("https", sent.getScheme());
        assertEquals(9000, sent.getPort());
        assertEquals("mybucket.storage.example.com", sent.getHost());
    }
}

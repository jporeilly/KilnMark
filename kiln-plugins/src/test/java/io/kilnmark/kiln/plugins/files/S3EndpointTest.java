package io.kilnmark.kiln.plugins.files;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;

import org.junit.jupiter.api.Test;

/** Where an S3 source's requests go, whatever this server's environment says (ADR 0034). */
class S3EndpointTest {

    /**
     * A source without an endpoint is sent to AWS's own HTTPS endpoint, never to one this server's
     * environment names (AWS_ENDPOINT_URL, aws.endpointUrl, a profile's endpoint_url), which could be
     * plain HTTP or a host the allow-list was not told of.
     */
    @Test
    void withoutAnEndpointRequestsGoToAwsOverHttps() throws Exception {
        String before = System.getProperty("aws.endpointUrl");
        System.setProperty("aws.endpointUrl", "http://127.0.0.1:9");
        try (S3FileStore aws = new S3FileStore(new S3FileStore.Settings(null, "eu-west-2", "b", null, "key", "secret",
            false), false)) {
            assertEquals(URI.create("https://s3.eu-west-2.amazonaws.com"), aws.endpoint());
        } finally {
            if (before == null) {
                System.clearProperty("aws.endpointUrl");
            } else {
                System.setProperty("aws.endpointUrl", before);
            }
        }
        try (S3FileStore own = new S3FileStore(new S3FileStore.Settings("https://storage.example.com:9000", null, "b",
            null, "key", "secret", true), false)) {
            assertEquals(URI.create("https://storage.example.com:9000"), own.endpoint());
        }
    }
}

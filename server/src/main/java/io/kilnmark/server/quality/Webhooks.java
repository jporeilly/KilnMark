package io.kilnmark.server.quality;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import org.jboss.logging.Logger;

/**
 * Posts rule results to webhooks. Only URLs on the administrator's allow-list are called, checked
 * again at send time in case the list changed since the rule was saved; redirects are not
 * followed, so an allowed URL cannot send the call elsewhere. One client serves every call.
 * There are no retries: a call that fails is logged, and the next evaluation calls again if the
 * rule still warns or fails.
 */
public final class Webhooks {

    private static final Logger LOG = Logger.getLogger(Webhooks.class);
    /** What {@link #send} answers when the URL is no longer on the allow-list. */
    public static final String NOT_ALLOWED = "not on the allow-list";

    private final HttpClient client;
    private final Duration timeout;
    private final List<String> allowList;

    public Webhooks(List<String> allowList, Duration timeout) {
        this.allowList = List.copyOf(allowList);
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(timeout).build();
    }

    /** Sends {@code body} (JSON) to {@code url}; returns the HTTP status, or a reason it was not sent. */
    public String send(String url, String body) {
        if (!RuleDraft.allowed(url, allowList)) {
            LOG.warnf("Webhook %s is no longer on the allow-list; not called", url);
            return NOT_ALLOWED;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                .header("Content-Type", "application/json").header("User-Agent", "Kilnmark")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<Void> r = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (r.statusCode() >= 300) {
                LOG.warnf("Webhook %s answered %d", url, r.statusCode());
            }
            return Integer.toString(r.statusCode());
        } catch (java.io.IOException e) {
            LOG.warnf("Webhook %s could not be called: %s", url, e.getMessage());
            return "failed: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
    }
}

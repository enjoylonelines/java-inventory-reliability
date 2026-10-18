package com.inventory.oracle;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Sends only to a deliberately configured local mock, never an actual ERP. */
@Component
@Profile("oracle")
@ConditionalOnProperty(name = "inventory.oracle.outbox.enabled", havingValue = "true")
public final class OracleMockHttpDelivery implements OracleMockDelivery {
    private final URI url;
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public OracleMockHttpDelivery(@Value("${inventory.oracle.outbox.mock-url}") String mockUrl, ObjectMapper json) {
        this.url = URI.create(mockUrl);
        String host = url.getHost();
        if (!"http".equals(url.getScheme()) || host == null
            || !(host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1"))
            || !"/apply".equals(url.getPath()) || url.getUserInfo() != null || url.getQuery() != null) {
            throw new IllegalArgumentException("mock-url must be a loopback HTTP /apply endpoint");
        }
        this.json = json;
    }

    @Override public void deliver(String eventId, String payload) throws Exception {
        String body = json.writeValueAsString(Map.of("key", eventId, "fault", "none", "idempotent", true,
            "payload", json.readTree(payload)));
        HttpRequest request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() != 200) throw new IllegalStateException("mock delivery failed: HTTP " + response.statusCode());
    }
}

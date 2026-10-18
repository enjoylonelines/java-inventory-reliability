package com.inventory.oracle;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OracleMockHttpDeliveryTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicInteger status = new AtomicInteger(200);
    private HttpServer server;
    private String url;

    @BeforeEach void startLoopbackMock() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/apply", exchange -> {
            try (exchange) {
                requestCount.incrementAndGet();
                method.set(exchange.getRequestMethod());
                contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(status.get(), -1);
            }
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/apply";
    }

    @AfterEach void stopLoopbackMock() {
        if (server != null) server.stop(0);
    }

    @Test void sendsEventIdAndOriginalPayloadToExplicitLoopbackMock() throws Exception {
        String payload = "{\"lines\":[{\"sku\":\"상품-A\",\"quantityDelta\":-2}],\"amount\":7.50}";
        new OracleMockHttpDelivery(url, json).deliver("event-123", payload);

        assertEquals(1, requestCount.get());
        assertEquals("POST", method.get());
        assertEquals("application/json", contentType.get());
        JsonNode body = json.readTree(requestBody.get());
        assertEquals("event-123", body.path("key").asText());
        assertEquals("none", body.path("fault").asText());
        assertTrue(body.path("idempotent").asBoolean());
        assertEquals(json.readTree(payload), body.path("payload"));
    }

    @Test void rejectsNonLoopbackAndUnexpectedEndpointConfigurations() {
        for (String invalid : List.of(
            "http://example.com/apply",
            "http://192.0.2.1/apply",
            "http://127.0.0.2/apply",
            "https://127.0.0.1/apply",
            "http://127.0.0.1/state",
            "http://127.0.0.1/apply?fault=lost-once",
            "http://user:password@127.0.0.1/apply")) {
            assertThrows(IllegalArgumentException.class, () -> new OracleMockHttpDelivery(invalid, json), invalid);
        }
        assertEquals(0, requestCount.get());
    }

    @Test void failureResponseIsReportedAndMalformedPayloadIsNotSent() {
        OracleMockHttpDelivery delivery = new OracleMockHttpDelivery(url, json);
        status.set(503);
        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> delivery.deliver("event-failed", "{\"value\":1}"));
        assertEquals("mock delivery failed: HTTP 503", error.getMessage());
        assertEquals(1, requestCount.get());

        assertThrows(Exception.class, () -> delivery.deliver("bad-payload", "not-json"));
        assertEquals(1, requestCount.get());
    }
}

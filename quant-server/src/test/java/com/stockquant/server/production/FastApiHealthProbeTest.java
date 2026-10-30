package com.stockquant.server.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FastApiHealthProbeTest {
    @Test
    void acceptsOnlyTheFixedUpAndVersionContract() throws Exception {
        try (Fixture fixture = fixture(200,
                "{\"status\":\"UP\",\"version\":\"1.3.1\"}")) {
            var snapshot = fixture.probe().probe();
            assertTrue(snapshot.healthy());
            assertEquals("FASTAPI_HEALTHY", snapshot.reason());
            assertEquals("1.3.1", snapshot.version());
        }
    }

    @Test
    void rejectsWrongVersionWithoutFollowingRedirects() throws Exception {
        try (Fixture fixture = fixture(200,
                "{\"status\":\"UP\",\"version\":\"unexpected\"}")) {
            var snapshot = fixture.probe().probe();
            assertFalse(snapshot.healthy());
            assertEquals("FASTAPI_HEALTH_PAYLOAD_INVALID", snapshot.reason());
        }
        try (Fixture fixture = fixture(302, "{}")) {
            var snapshot = fixture.probe().probe();
            assertFalse(snapshot.healthy());
            assertEquals("FASTAPI_HEALTH_HTTP_STATUS", snapshot.reason());
        }
    }

    private static Fixture fixture(int status, String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(
                InetAddress.getLoopbackAddress(), 0), 0);
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        server.createContext("/health", exchange -> {
            exchange.getResponseHeaders().set("Content-Type",
                    "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        URI uri = URI.create("http://127.0.0.1:"
                + server.getAddress().getPort() + "/health");
        return new Fixture(server, new FastApiHealthProbe(new ObjectMapper(),
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(1))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(), uri));
    }

    private record Fixture(HttpServer server, FastApiHealthProbe probe)
            implements AutoCloseable {
        @Override
        public void close() {
            server.stop(0);
        }
    }
}

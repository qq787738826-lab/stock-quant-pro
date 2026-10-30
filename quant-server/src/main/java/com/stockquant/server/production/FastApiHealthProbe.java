package com.stockquant.server.production;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Provider-free loopback readiness probe for the managed FastAPI service. */
@Component
public final class FastApiHealthProbe {
    static final URI HEALTH_URI = URI.create("http://127.0.0.1:8001/health");
    static final String EXPECTED_VERSION = "1.3.1";
    private static final int MAXIMUM_RESPONSE_BYTES = 65_536;

    private final ObjectMapper mapper;
    private final HttpClient client;
    private final URI healthUri;

    public FastApiHealthProbe(ObjectMapper mapper) {
        this(mapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(), HEALTH_URI);
    }

    FastApiHealthProbe(
            ObjectMapper mapper,
            HttpClient client,
            URI healthUri
    ) {
        this.mapper = mapper;
        this.client = client;
        this.healthUri = healthUri;
    }

    public Snapshot probe() {
        try {
            HttpRequest request = HttpRequest.newBuilder(healthUri)
                    .timeout(Duration.ofSeconds(3))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = client.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                return Snapshot.blocked("FASTAPI_HEALTH_HTTP_STATUS");
            }
            byte[] body;
            try (InputStream stream = response.body()) {
                body = stream.readNBytes(MAXIMUM_RESPONSE_BYTES + 1);
            }
            if (body == null || body.length < 2
                    || body.length > MAXIMUM_RESPONSE_BYTES) {
                return Snapshot.blocked("FASTAPI_HEALTH_PAYLOAD_INVALID");
            }
            var value = mapper.readTree(new String(body,
                    StandardCharsets.UTF_8));
            String status = value.path("status").asText();
            String version = value.path("version").asText();
            if (!"UP".equals(status) || !EXPECTED_VERSION.equals(version)) {
                return Snapshot.blocked("FASTAPI_HEALTH_PAYLOAD_INVALID");
            }
            return Snapshot.healthy(version);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return Snapshot.blocked("FASTAPI_HEALTH_INTERRUPTED");
        } catch (Exception error) {
            return Snapshot.blocked("FASTAPI_HEALTH_UNAVAILABLE");
        }
    }

    public record Snapshot(boolean healthy, String reason, String version) {
        private static Snapshot healthy(String version) {
            return new Snapshot(true, "FASTAPI_HEALTHY", version);
        }

        private static Snapshot blocked(String reason) {
            return new Snapshot(false, reason, "UNKNOWN");
        }
    }
}

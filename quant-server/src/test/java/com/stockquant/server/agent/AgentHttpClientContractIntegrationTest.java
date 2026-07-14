package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.QuantServerApplication;
import com.stockquant.server.agent.client.HttpAgentTeamClient;
import com.stockquant.server.agent.model.AgentModels.AgentTeamRequest;
import com.stockquant.server.agent.model.AgentModels.AgentTeamResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = QuantServerApplication.class)
@ActiveProfiles("agent-integration-test")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_TEST_DB_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "STOCK_QUANT_TEST_DB_PASSWORD", matches = ".+")
class AgentHttpClientContractIntegrationTest {

    private static final AtomicInteger CALL_COUNT = new AtomicInteger();
    private static final byte[] RESPONSE = resourceBytes(
            "/agent-team-contract/valid-agent-team-response.json");
    private static final HttpServer SERVER = startServer();
    private static volatile JsonNode capturedRequest;
    private static volatile String capturedMethod;
    private static volatile String capturedPath;
    private static volatile String capturedContentType;

    @Autowired
    private HttpAgentTeamClient client;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void clientBaseUrl(DynamicPropertyRegistry registry) {
        AgentPostgresTestEnvironment.registerDataSource(registry);
        registry.add("stockquant.agent-team.base-url",
                () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @Test
    void productionClientUsesFrozenCamelCaseIsoDateTimeContract() throws Exception {
        AgentTeamRequest request;
        try (InputStream input = getClass().getResourceAsStream(
                "/agent-team-contract/valid-agent-team-request.json")) {
            assertNotNull(input);
            request = objectMapper.readValue(input, AgentTeamRequest.class);
        }

        AgentTeamResponse response = client.analyze(request);

        assertEquals(1, CALL_COUNT.get());
        assertEquals("POST", capturedMethod);
        assertEquals("/agents/team/analyze", capturedPath);
        assertTrue(capturedContentType.startsWith("application/json"));
        assertTrue(capturedRequest.path("tradeDate").isTextual());
        assertEquals("2026-07-14", capturedRequest.path("tradeDate").textValue());
        assertTrue(capturedRequest.path("requestedAt").isTextual());
        assertEquals(Instant.parse("2026-07-14T05:01:00Z"),
                Instant.parse(capturedRequest.path("requestedAt").textValue()));
        assertTrue(capturedRequest.path("runIds").isObject());
        assertEquals(Set.of(
                        "dataQuality", "marketRegime", "technicalAnalysis",
                        "strategyBacktest", "announcementRisk", "positionRisk"),
                objectMapper.convertValue(capturedRequest.path("runIds"),
                        new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Long>>() {})
                        .keySet());
        assertTrue(capturedRequest.path("contextSnapshot").isObject());
        assertFalse(capturedRequest.has("task_id"));
        assertFalse(capturedRequest.has("run_ids"));
        assertFalse(capturedRequest.has("trade_date"));

        assertEquals(LocalDate.of(2026, 7, 14), response.tradeDate());
        assertEquals(6, response.agentRuns().size());
        assertEquals(Instant.parse("2026-07-14T05:02:00Z"), response.generatedAt());
        response.agentRuns().forEach(run ->
                assertEquals(Instant.parse("2026-07-14T05:02:00Z"), run.generatedAt()));
        assertEquals(Instant.parse("2026-07-14T05:02:00Z"),
                response.finalDecision().generatedAt());
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            server.createContext("/agents/team/analyze", AgentHttpClientContractIntegrationTest::handle);
            server.start();
            return server;
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        capturedMethod = exchange.getRequestMethod();
        capturedPath = exchange.getRequestURI().getPath();
        capturedContentType = exchange.getRequestHeaders().getFirst("Content-Type");
        capturedRequest = new ObjectMapper().readTree(exchange.getRequestBody());
        CALL_COUNT.incrementAndGet();
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, RESPONSE.length);
        exchange.getResponseBody().write(RESPONSE);
        exchange.close();
    }

    private static byte[] resourceBytes(String path) {
        try (InputStream input = AgentHttpClientContractIntegrationTest.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("共享契约夹具不存在");
            }
            return input.readAllBytes();
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }
}

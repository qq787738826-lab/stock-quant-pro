package com.stockquant.server.agent.backtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BacktestCanonicalHashServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final BacktestCanonicalHashService service =
            new BacktestCanonicalHashService(mapper);

    @Test
    void matchesCommittedGoldenVector() throws Exception {
        JsonNode input = mapper.readTree(resource(
                "agent/backtest-canonical-v1-input.json"));
        String canonical = resource(
                "agent/backtest-canonical-v1-canonical.txt").stripTrailing();
        String expectedHash = resource(
                "agent/backtest-canonical-v1-sha256.txt").strip();
        assertEquals(canonical, service.canonicalText(input));
        assertEquals(expectedHash, service.hash(input));
        assertEquals(expectedHash, BacktestCanonicalHashService.sha256(canonical));
    }

    @Test
    void sortsObjectsPreservesArraysAndDistinguishesMissingFromNull() throws Exception {
        JsonNode first = mapper.readTree(
                "{\"b\":2.000,\"a\":[2,1],\"present\":null}");
        JsonNode reordered = mapper.readTree(
                "{\"present\":null,\"a\":[2.0,1.00],\"b\":2}");
        JsonNode arrayChanged = mapper.readTree(
                "{\"b\":2,\"a\":[1,2],\"present\":null}");
        JsonNode missing = mapper.readTree("{\"b\":2,\"a\":[2,1]}");
        assertEquals(service.hash(first), service.hash(reordered));
        assertNotEquals(service.hash(first), service.hash(arrayChanged));
        assertNotEquals(service.hash(first), service.hash(missing));
    }

    @Test
    void rejectsNonFiniteNumbersAndNormalizedDuplicateKeys() throws Exception {
        ObjectNode invalid = mapper.createObjectNode();
        invalid.put("value", Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> service.hash(invalid));
        JsonNode duplicate = mapper.readTree("{\"é\":1,\"é\":2}");
        assertThrows(
                IllegalArgumentException.class,
                () -> service.canonicalText(duplicate));
    }

    private static String resource(String name) throws Exception {
        try (InputStream stream = Thread.currentThread()
                .getContextClassLoader().getResourceAsStream(name)) {
            if (stream == null) throw new IllegalStateException(name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

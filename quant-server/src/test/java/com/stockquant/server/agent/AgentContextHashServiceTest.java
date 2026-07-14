package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.service.AgentContextHashService;
import com.stockquant.server.agent.service.AgentContextSnapshotService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AgentContextHashServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentContextHashService service = new AgentContextHashService(objectMapper);

    @Test
    void objectKeyOrderDoesNotChangeHash() {
        ObjectNode first = objectMapper.createObjectNode().put("symbol", "600000").put("score", 1.00);
        ObjectNode second = objectMapper.createObjectNode().put("score", 1.0).put("symbol", "600000");
        assertEquals(service.hash(first), service.hash(second));
    }

    @Test
    void volatileFieldsDoNotChangeHash() {
        ObjectNode first = objectMapper.createObjectNode().put("taskId", 1).put("generatedAt", "first").put("value", 7);
        ObjectNode second = objectMapper.createObjectNode().put("taskId", 99).put("generatedAt", "second").put("value", 7);
        assertEquals(service.hash(first), service.hash(second));
    }

    @Test
    void stableBusinessValueChangesHash() {
        ObjectNode first = objectMapper.createObjectNode().put("value", 7);
        ObjectNode second = objectMapper.createObjectNode().put("value", 8);
        assertNotEquals(service.hash(first), service.hash(second));
    }

    @Test
    void snapshotContainsNineExplicitUnavailableReadOnlySections() {
        AgentContextSnapshotService snapshots = new AgentContextSnapshotService(objectMapper, service);
        var snapshot = snapshots.create("600000", AgentTestFixtures.TRADE_DATE);
        for (String section : new String[]{
                "security", "marketData", "marketBreadth", "scanResult", "technicalMetrics",
                "backtestContext", "securityEvents", "portfolioContext", "dataQualityContext"
        }) {
            assertFalse(snapshot.value().path(section).path("available").asBoolean());
            assertFalse(snapshot.value().path(section).path("reason").asText().isBlank());
            assertFalse(snapshot.value().path(section).path("queriedAt").asText().isBlank());
        }
    }
}

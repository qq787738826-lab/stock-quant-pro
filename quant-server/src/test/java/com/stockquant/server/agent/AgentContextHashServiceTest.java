package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.repository.AgentContextReadRepository;
import com.stockquant.server.agent.service.AgentContextHashService;
import com.stockquant.server.agent.service.AgentContextSnapshotService;
import com.stockquant.server.agent.service.AgentDataQualityContextService;
import com.stockquant.server.agent.service.AgentTechnicalMetricsService;
import com.stockquant.server.agent.service.AgentMarketBreadthContextService;
import com.stockquant.server.agent.service.AgentScanResultContextService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.*;

class AgentContextHashServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AgentContextHashService service = new AgentContextHashService(objectMapper);

    @Test
    void objectKeyOrderDoesNotChangeHash() {
        ObjectNode first = objectMapper.createObjectNode().put("symbol", "600000").put("score", 1.00);
        ObjectNode second = objectMapper.createObjectNode().put("score", 1.0).put("symbol", "600000");
        assertEquals(service.hash(first), service.hash(second));
    }

    @Test
    void volatileFieldsDoNotChangeHash() {
        ObjectNode first = objectMapper.createObjectNode()
                .put("taskId", 1).put("generatedAt", "first").put("value", 7);
        ObjectNode second = objectMapper.createObjectNode()
                .put("taskId", 99).put("generatedAt", "second").put("value", 7);
        assertEquals(service.hash(first), service.hash(second));
    }

    @Test
    void stableBusinessValueChangesHash() {
        assertNotEquals(
                service.hash(objectMapper.createObjectNode().put("value", 7)),
                service.hash(objectMapper.createObjectNode().put("value", 8))
        );
    }

    @Test
    void integralAndEquivalentDecimalValuesHaveSameHash() {
        assertAllEqualHashes(
                number(BigDecimal.TEN),
                number(new BigDecimal("10.0")),
                number(new BigDecimal("10.0000")),
                number(new BigDecimal("1E+1"))
        );
    }

    @Test
    void scientificNotationAndIntegralValueHaveSameHash() {
        assertAllEqualHashes(number(new BigDecimal("1000")), number(new BigDecimal("1E+3")));
    }

    @Test
    void zeroScalesAndNegativeNumberScalesHaveSameHash() {
        assertAllEqualHashes(
                number(BigDecimal.ZERO),
                number(new BigDecimal("0.0")),
                number(new BigDecimal("0.0000"))
        );
        assertAllEqualHashes(number(new BigDecimal("-10")), number(new BigDecimal("-10.00")));
    }

    @Test
    void differentMathematicalNumbersHaveDifferentHashes() {
        assertNotEquals(
                service.hash(number(new BigDecimal("10"))),
                service.hash(number(new BigDecimal("10.0001")))
        );
    }

    @Test
    void integerBeyondLongRangeKeepsFullPrecision() {
        BigInteger large = new BigInteger("1234567890123456789012345678901234567890");
        ObjectNode integral = objectMapper.createObjectNode().put("value", large);
        ObjectNode decimal = number(new BigDecimal(large).setScale(4));
        assertEquals(service.hash(integral), service.hash(decimal));
        assertNotEquals(
                service.hash(integral),
                service.hash(number(new BigDecimal("1234567890123456789012345678901234567891")))
        );
    }

    @Test
    void equivalentNumbersRemainStableInsideNestedObjectsAndArrays() {
        ObjectNode first = objectMapper.createObjectNode();
        first.putObject("nested").put("value", 10);
        first.putArray("items").add(new BigDecimal("0.0")).add(new BigDecimal("1E+3"));
        ObjectNode second = objectMapper.createObjectNode();
        second.putArray("items").add(0).add(1000);
        second.putObject("nested").put("value", new BigDecimal("10.0000"));
        assertEquals(service.hash(first), service.hash(second));
    }

    @Test
    void arrayOrderStillChangesHash() {
        ArrayNode first = objectMapper.createArrayNode().add(1).add(2);
        ArrayNode second = objectMapper.createArrayNode().add(2).add(1);
        assertNotEquals(service.hash(first), service.hash(second));
    }

    @Test
    void queriedAtStillDoesNotChangeHash() {
        ObjectNode first = objectMapper.createObjectNode()
                .put("queriedAt", "2026-07-14T05:00:00Z").put("value", 10);
        ObjectNode second = objectMapper.createObjectNode()
                .put("queriedAt", "2026-07-14T06:00:00Z").put("value", new BigDecimal("10.00"));
        assertEquals(service.hash(first), service.hash(second));
    }

    @Test
    void nonFiniteFloatingPointValuesAreRejected() {
        ObjectNode nan = objectMapper.createObjectNode();
        nan.set("value", DoubleNode.valueOf(Double.NaN));
        ObjectNode infinity = objectMapper.createObjectNode();
        infinity.set("value", DoubleNode.valueOf(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> service.hash(nan));
        assertThrows(IllegalArgumentException.class, () -> service.hash(infinity));
    }

    @Test
    void snapshotContainsStage2CResearchSectionsAndTwoLegacyUnavailableSections() {
        AgentContextReadRepository repository = mock(AgentContextReadRepository.class);
        when(repository.findSecurity("600000")).thenReturn(Optional.empty());
        when(repository.findQfqDailyBars("600000", AgentTestFixtures.TRADE_DATE)).thenReturn(List.of());
        when(repository.findAdjustTypes("600000", AgentTestFixtures.TRADE_DATE)).thenReturn(List.of());
        AgentMarketBreadthContextService breadth = mock(AgentMarketBreadthContextService.class);
        AgentScanResultContextService scan = mock(AgentScanResultContextService.class);
        ObjectNode unavailable = objectMapper.createObjectNode().put("available", false).put("reason", "test");
        when(breadth.create(anyString(), any(), any())).thenReturn(unavailable.deepCopy());
        when(scan.create(anyString(), any(), any())).thenReturn(unavailable.deepCopy());
        AgentContextSnapshotService snapshots = new AgentContextSnapshotService(
                objectMapper, service, repository,
                new AgentTechnicalMetricsService(), new AgentDataQualityContextService(), breadth, scan);
        var snapshot = snapshots.create("600000", AgentTestFixtures.TRADE_DATE);
        for (String section : new String[]{"securityEvents", "portfolioContext"}) {
            assertFalse(snapshot.value().path(section).path("available").asBoolean());
            assertEquals("该只读上下文尚未接入现有业务数据源",
                    snapshot.value().path(section).path("reason").asText());
            assertFalse(snapshot.value().path(section).path("queriedAt").asText().isBlank());
        }
        assertFalse(snapshot.value().path("marketBreadth").path("available").asBoolean());
        assertFalse(snapshot.value().path("scanResult").path("available").asBoolean());
        assertEquals("BACKTEST_INPUT_CUTOFF_UNVERIFIABLE",
                snapshot.value().path("backtestContext").path("reasonCode").asText());
        assertFalse(snapshot.value().path("security").path("available").asBoolean());
        assertFalse(snapshot.value().path("marketData").path("available").asBoolean());
        assertFalse(snapshot.value().path("technicalMetrics").path("available").asBoolean());
        assertTrue(snapshot.value().path("dataQualityContext").path("available").asBoolean());
    }

    private ObjectNode number(BigDecimal value) {
        ObjectNode node = objectMapper.createObjectNode();
        node.set("value", DecimalNode.valueOf(value));
        return node;
    }

    private void assertAllEqualHashes(ObjectNode... nodes) {
        String expected = service.hash(nodes[0]);
        for (ObjectNode node : nodes) {
            assertEquals(expected, service.hash(node));
        }
    }
}

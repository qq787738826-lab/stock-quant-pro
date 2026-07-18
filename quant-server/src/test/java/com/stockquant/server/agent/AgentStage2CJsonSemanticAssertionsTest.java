package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.LongNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.stockquant.server.agent.AgentStage2CReadonlyContextPostgresIntegrationTest.assertJsonSemanticallyEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentStage2CJsonSemanticAssertionsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsMathematicallyEqualNumbersAcrossNodeTypesAndScales() {
        assertJsonSemanticallyEquals(IntNode.valueOf(10), DecimalNode.valueOf(new BigDecimal("10.0")));
        assertJsonSemanticallyEquals(
                DecimalNode.valueOf(new BigDecimal("10.0000")),
                DecimalNode.valueOf(new BigDecimal("10.0")));
        assertJsonSemanticallyEquals(IntNode.valueOf(1), LongNode.valueOf(1L));
        assertJsonSemanticallyEquals(
                DecimalNode.valueOf(new BigDecimal("58.50")),
                DecimalNode.valueOf(new BigDecimal("58.5")));
    }

    @Test
    void acceptsObjectsWithDifferentFieldOrderAndEquivalentNestedNumbers() throws Exception {
        assertJsonSemanticallyEquals(
                mapper.readTree("""
                        {"marketBreadth":{"coverageRatio":10.0000,"available":true},"scanResult":null}
                        """),
                mapper.readTree("""
                        {"scanResult":null,"marketBreadth":{"available":true,"coverageRatio":10.0}}
                        """));
    }

    @Test
    void rejectsDifferentTypesValuesArrayOrderAndFieldSetsWithFullPaths() throws Exception {
        assertMismatchAt(
                "$.scanResult.sourceTaskId",
                "{\"scanResult\":{\"sourceTaskId\":1}}",
                "{\"scanResult\":{\"sourceTaskId\":\"1\"}}");
        assertMismatchAt(
                "$.marketBreadth.coverageRatio",
                "{\"marketBreadth\":{\"coverageRatio\":1}}",
                "{\"marketBreadth\":{\"coverageRatio\":1.0001}}");
        assertMismatchAt(
                "$.marketData.bars[0].open",
                "{\"marketData\":{\"bars\":[{\"open\":1},{\"open\":2}]}}",
                "{\"marketData\":{\"bars\":[{\"open\":2},{\"open\":1}]}}");
        assertMismatchAt(
                "$.security.name",
                "{\"security\":{\"symbol\":\"600000\",\"name\":\"Example\"}}",
                "{\"security\":{\"symbol\":\"600000\"}}");
        assertMismatchAt(
                "$.scanResult.sourceScanScore",
                "{\"scanResult\":{\"sourceScanScore\":null}}",
                "{\"scanResult\":{\"sourceScanScore\":0}}");
        assertMismatchAt(
                "$.marketBreadth.available",
                "{\"marketBreadth\":{\"available\":true}}",
                "{\"marketBreadth\":{\"available\":\"true\"}}");
    }

    private void assertMismatchAt(String expectedPath, String expectedJson, String actualJson) throws Exception {
        AssertionError error = assertThrows(AssertionError.class, () ->
                assertJsonSemanticallyEquals(mapper.readTree(expectedJson), mapper.readTree(actualJson)));
        assertTrue(error.getMessage().contains(expectedPath), error::getMessage);
    }
}

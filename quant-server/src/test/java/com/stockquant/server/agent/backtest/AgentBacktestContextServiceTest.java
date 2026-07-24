package com.stockquant.server.agent.backtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.core.backtest.BacktestEngine;
import com.stockquant.core.domain.Bar;
import com.stockquant.server.agent.backtest.MarketDataObservationRepository.ObservedDailyBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentBacktestContextServiceTest {

    private static final String SYMBOL = "600001";
    private static final LocalDate REQUEST_DATE = LocalDate.of(2025, 6, 30);
    private static final Instant QUERY_TIME = Instant.parse("2025-06-30T16:00:00Z");
    private static final Instant OBSERVED_AT = Instant.parse("2025-01-01T08:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper();
    private final BacktestCanonicalHashService hashes =
            new BacktestCanonicalHashService(mapper);

    @Test
    void buildsReplayableContextWithStableThirdsAndThreeDomainHashes() {
        List<ObservedDailyBar> observations = observations(121);
        AgentBacktestContextService service = service(observations, observations.size());
        JsonNode first = service.create(SYMBOL, REQUEST_DATE, QUERY_TIME);
        JsonNode second = service.create(SYMBOL, REQUEST_DATE, QUERY_TIME);

        assertTrue(first.path("available").asBoolean());
        assertEquals(121, first.path("barCount").asInt());
        assertEquals(List.of(41, 40, 40), List.of(
                first.path("subperiods").get(0).path("barCount").asInt(),
                first.path("subperiods").get(1).path("barCount").asInt(),
                first.path("subperiods").get(2).path("barCount").asInt()));
        assertEquals(List.of("EARLY", "MIDDLE", "LATE"), List.of(
                first.path("subperiods").get(0).path("name").asText(),
                first.path("subperiods").get(1).path("name").asText(),
                first.path("subperiods").get(2).path("name").asText()));
        for (String field : List.of(
                "inputDataHash",
                "strategyDefinitionHash",
                "backtestResultHash")) {
            assertTrue(first.path(field).asText().matches("^[0-9a-f]{64}$"));
            assertEquals(first.path(field), second.path(field));
        }
        assertEquals(
                BacktestContracts.parameters().maxHoldingDays(),
                first.path("strategy").path("parameters").path("maxHoldingDays").asInt());
        assertEquals(
                BacktestContracts.SPLIT_ALGORITHM,
                first.path("stability").path("splitAlgorithm").asText());
        assertTrue(first.path("pointInTimeGuaranteed").asBoolean());
        assertTrue(first.path("futureDataExcluded").asBoolean());
    }

    @Test
    void returnsMutuallyExclusiveUnavailableReasons() {
        JsonNode none = service(List.of(), 0)
                .create(SYMBOL, REQUEST_DATE, QUERY_TIME);
        assertEquals(
                BacktestContracts.NO_TRUSTED_PIT_DAILY_BARS,
                none.path("reasonCode").asText());

        JsonNode afterCutoff = service(List.of(), 4)
                .create(SYMBOL, REQUEST_DATE, QUERY_TIME);
        assertEquals(
                BacktestContracts.KNOWLEDGE_TIME_UNVERIFIABLE,
                afterCutoff.path("reasonCode").asText());

        JsonNode insufficient = service(observations(119), 119)
                .create(SYMBOL, REQUEST_DATE, QUERY_TIME);
        assertEquals(
                BacktestContracts.SAMPLE_INSUFFICIENT,
                insufficient.path("reasonCode").asText());
        assertEquals(119, insufficient.path("actualBars").asInt());

        List<ObservedDailyBar> missingRevision = observations(120).stream()
                .map(value -> new ObservedDailyBar(
                        value.physicalId(),
                        hashes.hash(observationPayload(
                                value.canonicalContentHash(), null)),
                        value.symbol(),
                        value.tradeDate(),
                        value.adjustType(),
                        value.open(),
                        value.high(),
                        value.low(),
                        value.close(),
                        value.volume(),
                        value.amount(),
                        value.turnoverRate(),
                        value.sourceCode(),
                        null,
                        value.datasetVersion(),
                        value.firstObservedAt(),
                        value.knownAt(),
                        value.recordedAt(),
                        value.canonicalContentHash(),
                        value.batchVersion(),
                        value.captureType(),
                        value.batchObservedAt(),
                        value.batchRecordedAt(),
                        value.sourceMetadataJson()))
                .toList();
        JsonNode sourceUnverifiable = service(missingRevision, 120)
                .create(SYMBOL, REQUEST_DATE, QUERY_TIME);
        assertEquals(
                BacktestContracts.SOURCE_REVISION_UNVERIFIABLE,
                sourceUnverifiable.path("reasonCode").asText());

        JsonNode future = service(observations(120), 120)
                .create(SYMBOL, REQUEST_DATE.plusDays(2), QUERY_TIME);
        assertEquals(
                BacktestContracts.FUTURE_REQUEST_DATE,
                future.path("reasonCode").asText());

        JsonNode beforeDecisionTime = service(observations(120), 120)
                .create(
                        SYMBOL,
                        REQUEST_DATE,
                        Instant.parse("2025-06-30T15:00:00.000000Z"));
        assertEquals(
                BacktestContracts.DECISION_TIME_NOT_REACHED,
                beforeDecisionTime.path("reasonCode").asText());
    }

    @Test
    void rejectsTamperedContentHashAndCapsWindowAtFiveHundred() {
        List<ObservedDailyBar> tampered = new ArrayList<>(observations(120));
        ObservedDailyBar original = tampered.get(0);
        tampered.set(0, new ObservedDailyBar(
                original.physicalId(),
                original.observationVersion(),
                original.symbol(),
                original.tradeDate(),
                original.adjustType(),
                original.open(),
                original.high(),
                original.low(),
                original.close(),
                original.volume(),
                original.amount(),
                original.turnoverRate(),
                original.sourceCode(),
                original.sourceRevision(),
                original.datasetVersion(),
                original.firstObservedAt(),
                original.knownAt(),
                original.recordedAt(),
                "0".repeat(64),
                original.batchVersion(),
                original.captureType(),
                original.batchObservedAt(),
                original.batchRecordedAt(),
                original.sourceMetadataJson()));
        JsonNode invalid = service(tampered, tampered.size())
                .create(SYMBOL, REQUEST_DATE, QUERY_TIME);
        assertEquals(
                BacktestContracts.HASH_MISMATCH,
                invalid.path("reasonCode").asText());

        List<ObservedDailyBar> fiveHundred = observations(500);
        JsonNode capped = service(fiveHundred, fiveHundred.size())
                .create(SYMBOL, REQUEST_DATE, QUERY_TIME);
        assertEquals(500, capped.path("barCount").asInt());
        assertFalse(capped.path("bars").get(499).path("tradeDate").asText().isBlank());
    }

    private AgentBacktestContextService service(
            List<ObservedDailyBar> observations,
            long totalCount
    ) {
        MarketDataObservationRepository repository =
                mock(MarketDataObservationRepository.class);
        when(repository.findAsOf(
                anyString(), any(), any(), anyInt())).thenReturn(observations);
        when(repository.countOnOrBefore(anyString(), any())).thenReturn(totalCount);
        return new AgentBacktestContextService(
                mapper,
                repository,
                hashes,
                new BacktestEngine());
    }

    private List<ObservedDailyBar> observations(int count) {
        List<ObservedDailyBar> values = new ArrayList<>();
        LocalDate start = REQUEST_DATE.minusDays(count - 1L);
        for (int index = 0; index < count; index++) {
            BigDecimal close = new BigDecimal("20")
                    .add(new BigDecimal("0.10").multiply(BigDecimal.valueOf(index)));
            Bar bar = new Bar(
                    SYMBOL,
                    start.plusDays(index),
                    close,
                    close.add(new BigDecimal("0.50")),
                    close.subtract(new BigDecimal("0.50")),
                    close,
                    10_000L + index,
                    new BigDecimal("1000000.00"),
                    new BigDecimal("0.5000"));
            String contentHash = hashes.hash(contentPayload(bar));
            String observationVersion = hashes.hash(
                    observationPayload(contentHash, "REVISION_1"));
            values.add(new ObservedDailyBar(
                    index + 1L,
                    observationVersion,
                    bar.symbol(),
                    bar.tradeDate(),
                    BacktestContracts.ADJUST_TYPE,
                    bar.open(),
                    bar.high(),
                    bar.low(),
                    bar.close(),
                    bar.volume(),
                    bar.amount(),
                    bar.turnoverRate(),
                    "TEST_FIXTURE",
                    "REVISION_1",
                    "TEST_DATASET_V1",
                    OBSERVED_AT,
                    OBSERVED_AT,
                    OBSERVED_AT.plusSeconds(1),
                    contentHash,
                    "TEST_BATCH_V1",
                    "TEST_FIXTURE",
                    OBSERVED_AT,
                    OBSERVED_AT.plusSeconds(1),
                    "{\"revisionSemantics\":\"TEST_FIXTURE\"}"));
        }
        return List.copyOf(values);
    }

    private ObjectNode contentPayload(Bar bar) {
        ObjectNode node = mapper.createObjectNode();
        node.put("canonicalContractVersion", BacktestContracts.CANONICAL_CONTRACT_VERSION);
        node.put("sourceCode", "TEST_FIXTURE");
        node.put("symbol", bar.symbol());
        node.put("tradeDate", bar.tradeDate().toString());
        node.put("adjustType", BacktestContracts.ADJUST_TYPE);
        node.put("open", bar.open());
        node.put("high", bar.high());
        node.put("low", bar.low());
        node.put("close", bar.close());
        node.put("volume", bar.volume());
        node.put("amount", bar.amount());
        node.put("turnoverRate", bar.turnoverRate());
        return node;
    }

    private ObjectNode observationPayload(
            String contentHash,
            String sourceRevision
    ) {
        ObjectNode node = mapper.createObjectNode();
        node.put("canonicalContractVersion", BacktestContracts.CANONICAL_CONTRACT_VERSION);
        node.put("batchVersion", "TEST_BATCH_V1");
        node.put("datasetVersion", "TEST_DATASET_V1");
        node.put("sourceCode", "TEST_FIXTURE");
        if (sourceRevision == null) node.putNull("sourceRevision");
        else node.put("sourceRevision", sourceRevision);
        node.put("firstObservedAt",
                BacktestCanonicalHashService.formatInstant(OBSERVED_AT));
        node.put("knownAt", BacktestCanonicalHashService.formatInstant(OBSERVED_AT));
        node.put("canonicalContentHash", contentHash);
        return node;
    }
}

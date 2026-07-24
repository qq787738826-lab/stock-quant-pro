package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.core.backtest.BacktestEngine;
import com.stockquant.core.domain.Bar;
import com.stockquant.server.agent.backtest.AgentBacktestContextService;
import com.stockquant.server.agent.backtest.BacktestCanonicalHashService;
import com.stockquant.server.agent.backtest.BacktestContracts;
import com.stockquant.server.agent.backtest.MarketDataObservationRepository;
import com.stockquant.server.agent.backtest.MarketDataObservationRepository.ObservedDailyBar;
import com.stockquant.server.agent.model.AgentModels.AgentTeamRequest;
import com.stockquant.server.agent.service.AgentContextHashService;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class AgentStage2FTestFixtures {

    enum Scenario {
        PASS,
        WARN,
        BLOCKED,
        UNAVAILABLE,
        INVALID_HASH
    }

    private static final ObjectMapper MAPPER =
            new ObjectMapper().findAndRegisterModules();
    private static final BacktestCanonicalHashService DOMAIN_HASHES =
            new BacktestCanonicalHashService(MAPPER);
    private static final AgentContextHashService CONTEXT_HASHES =
            new AgentContextHashService(MAPPER);

    private AgentStage2FTestFixtures() {
    }

    static AgentTeamRequest request(Scenario scenario) {
        AgentStage2ETestFixtures.Scenario baseScenario = switch (scenario) {
            case WARN -> AgentStage2ETestFixtures.Scenario.WARN;
            case BLOCKED -> AgentStage2ETestFixtures.Scenario.BLOCKED;
            default -> AgentStage2ETestFixtures.Scenario.PASS;
        };
        AgentTeamRequest base = AgentStage2ETestFixtures.request(baseScenario);
        ObjectNode snapshot = base.contextSnapshot().deepCopy();
        ObjectNode backtest = scenario == Scenario.UNAVAILABLE
                ? unavailableContext(base.symbol(), base.tradeDate())
                : reliableContext(base.symbol(), base.tradeDate());
        if (scenario == Scenario.INVALID_HASH) {
            backtest.put("inputDataHash", "0".repeat(64));
        }
        snapshot.set("backtestContext", backtest);
        String contextHash = CONTEXT_HASHES.hash(snapshot);
        return new AgentTeamRequest(
                base.schemaVersion(),
                base.taskId(),
                base.runIds(),
                base.symbol(),
                base.tradeDate(),
                contextHash,
                base.contextSchemaVersion(),
                BacktestContracts.RULE_VERSION,
                base.executionMode(),
                snapshot,
                base.requestedAt());
    }

    private static ObjectNode reliableContext(String symbol, LocalDate requestDate) {
        Instant queriedAt = requestDate.plusDays(1)
                .atStartOfDay(BacktestContracts.MARKET_ZONE)
                .toInstant();
        Instant observedAt = requestDate.minusDays(1)
                .atTime(8, 0)
                .atZone(BacktestContracts.MARKET_ZONE)
                .toInstant();
        List<ObservedDailyBar> observations = observations(
                symbol,
                requestDate,
                observedAt);
        MarketDataObservationRepository repository =
                mock(MarketDataObservationRepository.class);
        when(repository.findAsOf(
                anyString(), any(), any(), anyInt())).thenReturn(observations);
        when(repository.countOnOrBefore(anyString(), any()))
                .thenReturn((long) observations.size());
        AgentBacktestContextService service = new AgentBacktestContextService(
                MAPPER,
                repository,
                DOMAIN_HASHES,
                new BacktestEngine());
        return service.create(symbol, requestDate, queriedAt);
    }

    private static ObjectNode unavailableContext(
            String symbol,
            LocalDate requestDate
    ) {
        Instant queriedAt = requestDate.plusDays(1)
                .atStartOfDay(BacktestContracts.MARKET_ZONE)
                .toInstant();
        MarketDataObservationRepository repository =
                mock(MarketDataObservationRepository.class);
        when(repository.findAsOf(
                anyString(), any(), any(), anyInt())).thenReturn(List.of());
        when(repository.countOnOrBefore(anyString(), any())).thenReturn(1L);
        AgentBacktestContextService service = new AgentBacktestContextService(
                MAPPER,
                repository,
                DOMAIN_HASHES,
                new BacktestEngine());
        return service.create(symbol, requestDate, queriedAt);
    }

    private static List<ObservedDailyBar> observations(
            String symbol,
            LocalDate requestDate,
            Instant observedAt
    ) {
        List<ObservedDailyBar> values = new ArrayList<>();
        LocalDate start = requestDate.minusDays(119);
        for (int index = 0; index < 120; index++) {
            BigDecimal close = new BigDecimal("20")
                    .add(new BigDecimal("0.10").multiply(BigDecimal.valueOf(index)));
            Bar bar = new Bar(
                    symbol,
                    start.plusDays(index),
                    close,
                    close.add(new BigDecimal("0.50")),
                    close.subtract(new BigDecimal("0.50")),
                    close,
                    10_000L + index,
                    new BigDecimal("1000000.00"),
                    new BigDecimal("0.5000"));
            String contentHash = DOMAIN_HASHES.hash(contentPayload(bar));
            String observationVersion = DOMAIN_HASHES.hash(
                    observationPayload(contentHash, observedAt));
            values.add(new ObservedDailyBar(
                    index + 1L,
                    observationVersion,
                    symbol,
                    bar.tradeDate(),
                    BacktestContracts.ADJUST_TYPE,
                    bar.open(),
                    bar.high(),
                    bar.low(),
                    bar.close(),
                    bar.volume(),
                    bar.amount(),
                    bar.turnoverRate(),
                    "TEST_FIXTURE_STAGE_2F",
                    "REVISION_1",
                    "TEST_DATASET_V1",
                    observedAt,
                    observedAt,
                    observedAt.plusSeconds(1),
                    contentHash,
                    "TEST_BATCH_V1",
                    "TEST_FIXTURE",
                    observedAt,
                    observedAt.plusSeconds(1),
                    "{\"revisionSemantics\":\"TEST_FIXTURE\"}"));
        }
        return List.copyOf(values);
    }

    private static ObjectNode contentPayload(Bar bar) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("canonicalContractVersion", BacktestContracts.CANONICAL_CONTRACT_VERSION);
        node.put("sourceCode", "TEST_FIXTURE_STAGE_2F");
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

    private static ObjectNode observationPayload(
            String contentHash,
            Instant observedAt
    ) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("canonicalContractVersion", BacktestContracts.CANONICAL_CONTRACT_VERSION);
        node.put("batchVersion", "TEST_BATCH_V1");
        node.put("datasetVersion", "TEST_DATASET_V1");
        node.put("sourceCode", "TEST_FIXTURE_STAGE_2F");
        node.put("sourceRevision", "REVISION_1");
        node.put(
                "firstObservedAt",
                BacktestCanonicalHashService.formatInstant(observedAt));
        node.put(
                "knownAt",
                BacktestCanonicalHashService.formatInstant(observedAt));
        node.put("canonicalContentHash", contentHash);
        return node;
    }
}

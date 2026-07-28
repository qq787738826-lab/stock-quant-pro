package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.backtest.BacktestCanonicalHashService;
import com.stockquant.server.agent.config.AgentShadowProperties;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactRequest;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.ProviderVersion;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RevisionQualification;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RunNamespace;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderNeutralMarketFactsV2Test {

    private final ObjectMapper mapper = new ObjectMapper();
    private final PitMarketFactsCanonicalService canonical =
            new PitMarketFactsCanonicalService(
                    mapper, new BacktestCanonicalHashService(mapper));

    @Test
    void providerContractDoesNotExposeJavaAuthorityFields() {
        Set<String> responseFields = Arrays.stream(
                        MarketFactProviderModels.MarketFactResponse.class
                                .getRecordComponents())
                .map(RecordComponent::getName)
                .collect(java.util.stream.Collectors.toSet());
        for (String forbidden : Set.of(
                "firstObservedAt",
                "knownAt",
                "recordedAt",
                "datasetVersion",
                "observationVersion",
                "canonicalContentHash")) {
            assertFalse(responseFields.contains(forbidden));
        }
        assertEquals(
                Set.of(FactType.values()),
                new MockMarketFactProvider(
                        mapper,
                        MockMarketFactProvider.Scenario.NORMAL)
                        .capability().supportedFactTypes());
    }

    @Test
    void matchesCommittedCrossLanguageCanonicalGoldenVector()
            throws Exception {
        JsonNode input = mapper.readTree(resource(
                "agent/pit-market-facts-canonical-v2-input.json"));
        String expectedCanonical = resource(
                "agent/pit-market-facts-canonical-v2-canonical.txt")
                .stripTrailing();
        String expectedHash = resource(
                "agent/pit-market-facts-canonical-v2-sha256.txt").strip();
        assertEquals(expectedCanonical, canonical.canonicalText(input));
        assertEquals(expectedHash, canonical.hash(input));
    }

    @Test
    void freezesAllEighteenQfqGoldenScenariosAndRoundingVector()
            throws Exception {
        JsonNode fixture = mapper.readTree(resource(
                "agent/qfq-as-of-engine-v1-golden-scenarios.json"));
        assertEquals(PitMarketFactsContracts.QFQ_ENGINE_VERSION,
                fixture.path("engineVersion").asText());
        assertEquals(PitMarketFactsContracts.FACTOR_COVERAGE_MODE,
                fixture.path("factorCoverageMode").asText());
        JsonNode scenarios = fixture.path("scenarios");
        assertTrue(scenarios.isArray());
        assertEquals(18, scenarios.size());
        Set<String> names = new java.util.HashSet<>();
        for (int index = 0; index < scenarios.size(); index++) {
            assertEquals(index + 1, scenarios.get(index).path("id").asInt());
            assertTrue(names.add(scenarios.get(index).path("name").asText()));
        }
        JsonNode vector = fixture.path("representativeCalculation");
        BigDecimal divided = new BigDecimal(vector.path("rawPrice").asText())
                .multiply(new BigDecimal(vector.path("factor").asText()))
                .divide(new BigDecimal(vector.path("anchorFactor").asText()),
                        fixture.path("divisionScale").asInt(),
                        RoundingMode.HALF_UP);
        assertEquals(vector.path("divisionResult").asText(),
                divided.toPlainString());
        assertEquals(vector.path("outputPrice").asText(),
                divided.setScale(
                        fixture.path("outputPriceScale").asInt(),
                        RoundingMode.HALF_UP).toPlainString());
    }

    @Test
    void mockIsSyntheticDeterministicAndCoversSafeFailures() {
        MarketFactRequest request = request();
        MockMarketFactProvider first = new MockMarketFactProvider(
                mapper, MockMarketFactProvider.Scenario.NORMAL);
        MockMarketFactProvider second = new MockMarketFactProvider(
                mapper, MockMarketFactProvider.Scenario.NORMAL);
        var firstResponse = first.fetch(request);
        var secondResponse = second.fetch(request);
        assertEquals(firstResponse, secondResponse);
        assertTrue(firstResponse.complete());
        assertFalse(firstResponse.rawDailyBars().isEmpty());
        assertFalse(firstResponse.adjustmentFactors().isEmpty());
        assertFalse(firstResponse.tradingCalendar().isEmpty());
        assertFalse(firstResponse.corporateActions().isEmpty());
        assertEquals(1, first.fetchCount());
        assertEquals(0,
                firstResponse.providerMetadata().path("networkCalls").asInt());

        for (MockMarketFactProvider.Scenario scenario : Set.of(
                MockMarketFactProvider.Scenario.PARTIAL,
                MockMarketFactProvider.Scenario.ERROR,
                MockMarketFactProvider.Scenario.TIMEOUT,
                MockMarketFactProvider.Scenario.RATE_LIMITED,
                MockMarketFactProvider.Scenario.STRUCTURE_CHANGED)) {
            var response = new MockMarketFactProvider(
                    mapper, scenario).fetch(request);
            assertFalse(response.complete());
            assertFalse(response.errors().isEmpty());
        }
        assertTrue(new MockMarketFactProvider(
                mapper, MockMarketFactProvider.Scenario.EMPTY)
                .fetch(request).complete());
    }

    @Test
    void unqualifiedProviderRevisionCannotBeInvented() {
        assertThrows(IllegalArgumentException.class, () ->
                new ProviderVersion(
                        "FIXTURE", "LOCAL_REVISION", null, null, null,
                        RevisionQualification.SYSTEM_KNOWLEDGE_ONLY));
        assertThrows(IllegalArgumentException.class, () ->
                new ProviderVersion(
                        "FIXTURE", "PROVIDER_REVISION", null, null, null,
                        RevisionQualification.PROVIDER_VERIFIED));
    }

    @Test
    void providerDtoRejectsInvalidOhlcFactorAndDatabaseRounding() {
        ProviderVersion version = new ProviderVersion(
                "FIXTURE", null, null, null, null,
                RevisionQualification.SYSTEM_KNOWLEDGE_ONLY);
        ObjectNode rawFields = mapper.createObjectNode();
        assertThrows(IllegalArgumentException.class, () ->
                new MarketFactProviderModels.RawDailyBar(
                        "000001", "SZSE", LocalDate.of(2026, 7, 27),
                        new BigDecimal("10"), new BigDecimal("9"),
                        new BigDecimal("8"), new BigDecimal("10"),
                        new BigDecimal("100"), null, null,
                        version, rawFields));
        assertThrows(IllegalArgumentException.class, () ->
                new MarketFactProviderModels.AdjustmentFactor(
                        "000001", LocalDate.of(2026, 7, 27),
                        "QFQ", "DAILY_EXACT", BigDecimal.ZERO,
                        version, rawFields));
        assertThrows(IllegalArgumentException.class, () ->
                new MarketFactProviderModels.AdjustmentFactor(
                        "000001", LocalDate.of(2026, 7, 27),
                        "QFQ", "DAILY_EXACT",
                        new BigDecimal("1.1234567890123456789"),
                        version, rawFields));
    }

    @Test
    void ifindSkeletonAlwaysStopsBeforeNetwork() {
        IFindTrialProperties properties = new IFindTrialProperties();
        properties.setEnabled(true);
        properties.setActivationGate("BLOCKED");
        IFindDisabledMarketFactProvider provider =
                new IFindDisabledMarketFactProvider(properties);
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> provider.fetch(request()));
        assertTrue(error.getMessage().startsWith(
                PitMarketFactsContracts.IFIND_GATE_NOT_PASSED));
        assertFalse(provider.networkClientCreated());
    }

    @Test
    void sanitizerRecursesWhitelistsAndMatchesCanonicalHash() {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("provider", "DEMO");
        raw.put("Authorization", "must disappear");
        raw.put("discarded", "not whitelisted");
        ObjectNode payload = raw.putObject("payload");
        payload.put("symbol", "000001");
        payload.put("sessionToken", "must disappear");
        payload.putArray("records")
                .addObject()
                .put("tradeDate", "2026-07-27")
                .put("Cookie", "must disappear");
        OfflineFixtureSanitizer sanitizer =
                new OfflineFixtureSanitizer(mapper, canonical);
        var result = sanitizer.sanitize(raw, Set.of("provider", "payload"));
        assertFalse(result.canonicalText().toLowerCase()
                .contains("authorization"));
        assertFalse(result.canonicalText().toLowerCase().contains("cookie"));
        assertFalse(result.canonicalText().toLowerCase().contains("token"));
        assertEquals(canonical.hash(result.value()), result.sha256());
        sanitizer.rejectSensitive(result.value());
        ObjectNode unsafeValue = mapper.createObjectNode();
        unsafeValue.put("sourceFile",
                "C:\\Users\\local-user\\private\\response.json");
        assertThrows(IllegalArgumentException.class, () ->
                sanitizer.sanitize(unsafeValue, Set.of("sourceFile")));
        ObjectNode credentialUrl = mapper.createObjectNode();
        credentialUrl.put("sourceUrl",
                "https://local-user:secret@example.invalid/data");
        assertThrows(IllegalArgumentException.class, () ->
                sanitizer.sanitize(credentialUrl, Set.of("sourceUrl")));
    }

    @Test
    void pitV2ShadowIsOptInAndCannotUseScheduler() {
        AgentShadowProperties properties = new AgentShadowProperties();
        properties.setRuleVersion(PitMarketFactsContracts.RULE_VERSION);
        assertThrows(IllegalArgumentException.class,
                properties::validateFrozenContract);
        properties.setTestDemoPitV2Enabled(true);
        properties.validateFrozenContract();
        properties.setSchedulerEnabled(true);
        assertThrows(IllegalArgumentException.class,
                properties::validateFrozenContract);
    }

    private MarketFactRequest request() {
        return new MarketFactRequest(
                RunNamespace.TEST,
                MockMarketFactProvider.PROVIDER_CODE,
                "000001.SZSE",
                "000001",
                "SZSE",
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 7, 27),
                Set.of(FactType.values()),
                Duration.ofSeconds(5));
    }

    private static String resource(String name) throws Exception {
        try (InputStream input = Thread.currentThread()
                .getContextClassLoader().getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException(name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

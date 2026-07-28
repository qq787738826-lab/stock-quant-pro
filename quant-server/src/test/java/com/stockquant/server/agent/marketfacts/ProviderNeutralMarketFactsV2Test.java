package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.backtest.BacktestCanonicalHashService;
import com.stockquant.server.agent.config.AgentShadowProperties;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FieldQualification;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFieldSemantic;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFieldUnit;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactRequest;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.ProviderVersion;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RevisionQualification;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RunNamespace;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.AssuranceLevel;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.UsageQualification;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.ContentQualification;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
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
        ProviderVersion version = new ProviderVersion(
                null, null, null, null, null,
                RevisionQualification.SYSTEM_KNOWLEDGE_ONLY);
        var fact = new MarketFactProviderModels.RawDailyBar(
                "SECURITY:000001.SZSE",
                "000001",
                "SZSE",
                LocalDate.of(2026, 7, 27),
                new BigDecimal("10.0000"),
                new BigDecimal("10.2000"),
                new BigDecimal("9.8000"),
                new BigDecimal("10.1000"),
                field(
                        new BigDecimal("1000000.0000"),
                        MarketFieldUnit.SHARES,
                        MarketFieldSemantic.TRADED_VOLUME),
                missing(
                        MarketFieldUnit.CNY,
                        MarketFieldSemantic.TRADED_AMOUNT),
                field(
                        BigDecimal.ZERO,
                        MarketFieldUnit.RATIO,
                        MarketFieldSemantic.TURNOVER_RATE),
                version,
                mapper.createObjectNode());
        JsonNode projected = canonical.contentPayload(
                FactType.RAW_DAILY_BAR,
                MockMarketFactProvider.PROVIDER_CODE,
                fact.sourceIdentity(),
                MarketFactProviderModels.naturalKey(
                        FactType.RAW_DAILY_BAR, fact),
                fact,
                new ContentQualification(
                        AssuranceLevel.SYSTEM_KNOWLEDGE_PIT,
                        UsageQualification.TEST_DEMO_ONLY,
                        false, true, true, true, true));
        assertEquals(
                canonical.canonicalText(input),
                canonical.canonicalText(projected));
        assertEquals(expectedHash, canonical.hash(projected));
    }

    @Test
    void freezesAllEighteenExecutableQfqGoldenScenarios()
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
            JsonNode scenario = scenarios.get(index);
            assertEquals(index + 1, scenario.path("id").asInt());
            assertTrue(names.add(scenario.path("name").asText()));
            assertTrue(scenario.path("input").path("rawObservations").isArray());
            assertTrue(scenario.path("input").path("factorObservations").isArray());
            assertTrue(scenario.path("input").path("calendarObservations").isArray());
            assertTrue(scenario.path("input")
                    .path("corporateActionObservations").isArray());
            assertTrue(scenario.path("input").path("factorPredecessors").isArray());
            assertTrue(scenario.path("expectedCanonicalResult").isObject());
            assertTrue(scenario.path("expectedCanonicalHash").asText()
                    .matches("[0-9a-f]{64}"));
        }
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
        assertThrows(IllegalArgumentException.class, () ->
                new ProviderVersion(
                        "DATASET", "REVISION", "SNAPSHOT",
                        java.time.Instant.parse("2026-07-27T07:10:00Z"),
                        java.time.Instant.parse("2026-07-27T07:09:59Z"),
                        RevisionQualification.PROVIDER_VERIFIED));
    }

    @Test
    void providerDtoRejectsInvalidOhlcFactorAndDatabaseRounding() {
        ProviderVersion version = new ProviderVersion(
                null, null, null, null, null,
                RevisionQualification.SYSTEM_KNOWLEDGE_ONLY);
        ObjectNode rawFields = mapper.createObjectNode();
        assertThrows(IllegalArgumentException.class, () ->
                new MarketFactProviderModels.RawDailyBar(
                        MockMarketFactProvider.rawSourceIdentity(
                                "000001", "SZSE"),
                        "000001", "SZSE", LocalDate.of(2026, 7, 27),
                        new BigDecimal("10"), new BigDecimal("9"),
                        new BigDecimal("8"), new BigDecimal("10"),
                        field(new BigDecimal("100"),
                                MarketFieldUnit.SHARES,
                                MarketFieldSemantic.TRADED_VOLUME),
                        missing(MarketFieldUnit.CNY,
                                MarketFieldSemantic.TRADED_AMOUNT),
                        missing(MarketFieldUnit.RATIO,
                                MarketFieldSemantic.TURNOVER_RATE),
                        version, rawFields));
        assertThrows(IllegalArgumentException.class, () ->
                new MarketFactProviderModels.AdjustmentFactor(
                        MockMarketFactProvider.factorSourceIdentity(
                                "000001", "SZSE"),
                        "000001", LocalDate.of(2026, 7, 27),
                        "QFQ", "DAILY_EXACT", BigDecimal.ZERO,
                        version, rawFields));
        assertThrows(IllegalArgumentException.class, () ->
                new MarketFactProviderModels.AdjustmentFactor(
                        MockMarketFactProvider.factorSourceIdentity(
                                "000001", "SZSE"),
                        "000001", LocalDate.of(2026, 7, 27),
                        "QFQ", "DAILY_EXACT",
                new BigDecimal("1.1234567890123456789"),
                        version, rawFields));
    }

    @Test
    void distinguishesMissingUnverifiedAndExplicitZeroMarketFields() {
        assertEquals(
                BigDecimal.ZERO,
                new MarketFactProviderModels.QualifiedMarketField(
                        BigDecimal.ZERO,
                        FieldQualification.PRESENT_VERIFIED,
                        MarketFieldUnit.SHARES,
                        MarketFieldSemantic.TRADED_VOLUME).value());
        assertEquals(
                FieldQualification.MISSING,
                missing(
                        MarketFieldUnit.CNY,
                        MarketFieldSemantic.TRADED_AMOUNT).qualification());
        assertThrows(IllegalArgumentException.class, () ->
                new MarketFactProviderModels.QualifiedMarketField(
                        BigDecimal.ZERO,
                        FieldQualification.MISSING,
                        MarketFieldUnit.CNY,
                        MarketFieldSemantic.TRADED_AMOUNT));
        assertThrows(IllegalArgumentException.class, () ->
                new MarketFactProviderModels.QualifiedMarketField(
                        null,
                        FieldQualification.PRESENT_UNVERIFIED,
                        MarketFieldUnit.RATIO,
                        MarketFieldSemantic.TURNOVER_RATE));
        ProviderVersion version = new ProviderVersion(
                null, null, null, null, null,
                RevisionQualification.SYSTEM_KNOWLEDGE_ONLY);
        assertThrows(IllegalArgumentException.class, () ->
                new MarketFactProviderModels.RawDailyBar(
                        MockMarketFactProvider.rawSourceIdentity(
                                "000001", "SZSE"),
                        "000001", "SZSE",
                        LocalDate.of(2026, 7, 27),
                        new BigDecimal("10"),
                        new BigDecimal("11"),
                        new BigDecimal("9"),
                        new BigDecimal("10"),
                        field(
                                new BigDecimal("100"),
                                MarketFieldUnit.CNY,
                                MarketFieldSemantic.TRADED_AMOUNT),
                        missing(
                                MarketFieldUnit.CNY,
                                MarketFieldSemantic.TRADED_AMOUNT),
                        missing(
                                MarketFieldUnit.RATIO,
                                MarketFieldSemantic.TURNOVER_RATE),
                        version,
                        mapper.createObjectNode()));
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

    private static MarketFactProviderModels.QualifiedMarketField field(
            BigDecimal value,
            MarketFieldUnit unit,
            MarketFieldSemantic semantic
    ) {
        return new MarketFactProviderModels.QualifiedMarketField(
                value, FieldQualification.PRESENT_VERIFIED, unit, semantic);
    }

    private static MarketFactProviderModels.QualifiedMarketField missing(
            MarketFieldUnit unit,
            MarketFieldSemantic semantic
    ) {
        return new MarketFactProviderModels.QualifiedMarketField(
                null, FieldQualification.MISSING, unit, semantic);
    }
}

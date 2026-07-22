package com.stockquant.server.agent.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.ingestion.IngestionModels.AssuranceLevel;
import com.stockquant.server.agent.ingestion.IngestionModels.DatasetType;
import com.stockquant.server.agent.ingestion.IngestionModels.IngestionRun;
import com.stockquant.server.agent.ingestion.IngestionModels.ManifestContractVersion;
import com.stockquant.server.agent.ingestion.IngestionModels.OperationType;
import com.stockquant.server.agent.ingestion.IngestionModels.RunNamespace;
import com.stockquant.server.agent.ingestion.IngestionModels.RunStatus;
import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels.MaterializedSecurityEvent;
import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels.NormalizationOutcome;
import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels.ParsedSecurityStatusRaw;
import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels.SecurityEventManifestEntry;
import com.stockquant.server.agent.temporal.MarketExchange;
import com.stockquant.server.agent.temporal.SecurityStatusEventPayloadContract;
import com.stockquant.server.agent.temporal.SecurityStatusEventPayloadContract.SecurityStatusState;
import com.stockquant.server.agent.temporal.SecurityStatusEventType;
import com.stockquant.server.agent.temporal.TemporalModels.DatasetVersion;
import com.stockquant.server.agent.temporal.TemporalTrustLevel;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityEventMaterializationContractTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SecurityStatusRawTestV1Parser parser = new SecurityStatusRawTestV1Parser();
    private final SecurityStatusEventClassifier classifier = new SecurityStatusEventClassifier();
    private final SecurityEventCanonicalHasher hasher = new SecurityEventCanonicalHasher();

    @Test
    void strictRawContractRejectsMissingExtraWrongTypesAndInvalidActiveState() throws Exception {
        ParsedSecurityStatusRaw value = parser.parse(raw("600000", state(true, true, false)));
        assertEquals("600000", value.symbol());
        assertEquals(MarketExchange.SSE, value.state().exchange());

        assertThrows(IllegalArgumentException.class, () -> parser.parse(mapper.readTree("""
                {"schemaVersion":"SECURITY_STATUS_RAW_TEST_V1","symbol":"600000",
                 "state":{"exchange":"SSE","board":"MAIN","listed":true,
                          "active":true,"isSt":false},"extra":1}
                """)));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(mapper.readTree("""
                {"schemaVersion":"SECURITY_STATUS_RAW_TEST_V1","symbol":"600000",
                 "state":{"exchange":"SSE","board":"MAIN","listed":true,"active":true}}
                """)));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(mapper.readTree("""
                {"schemaVersion":"SECURITY_STATUS_RAW_TEST_V1","symbol":600000,
                 "state":{"exchange":"SSE","board":"MAIN","listed":true,
                          "active":true,"isSt":false}}
                """)));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(mapper.readTree("""
                {"schemaVersion":"SECURITY_STATUS_RAW_TEST_V1","symbol":"600000",
                 "state":{"exchange":"SSE","board":"MAIN","listed":"true",
                          "active":true,"isSt":false}}
                """)));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(mapper.readTree("""
                {"schemaVersion":"SECURITY_STATUS_RAW_TEST_V1","symbol":"600000",
                 "state":{"exchange":"SSE","board":"MAIN","listed":false,
                          "active":true,"isSt":false}}
                """)));
        assertThrows(SecurityStatusRawTestV1Parser.UnsupportedRawContractException.class,
                () -> parser.parse(mapper.readTree("""
                        {"schemaVersion":"UNKNOWN","symbol":"600000",
                         "state":{"exchange":"SSE","board":"MAIN","listed":true,
                                  "active":true,"isSt":false}}
                        """)));
    }

    @Test
    void classifierCoversInitialAllFrozenTransitionsAndNoStateChange() throws Exception {
        ParsedSecurityStatusRaw initial = parsed("600000", state(false, false, false));
        assertEquals(SecurityStatusEventType.FULL_STATUS_SNAPSHOT,
                classifier.classify(initial, null).eventType());

        MaterializedSecurityEvent base = event(
                SecurityStatusEventType.FULL_STATUS_SNAPSHOT,
                "600000", state(true, true, false), null);
        assertTrue(classifier.classify(parsed("600000", state(true, true, false)), base)
                .noStateChange());
        assertEquals(SecurityStatusEventType.ST_CHANGE,
                classifier.classify(parsed("600000", state(true, true, true)), base).eventType());
        assertEquals(SecurityStatusEventType.BOARD_CHANGE,
                classifier.classify(parsed("600000",
                        new SecurityStatusState(MarketExchange.SSE, "MAIN_B", true, true, false)),
                        base).eventType());
        assertEquals(SecurityStatusEventType.ACTIVE_CHANGE,
                classifier.classify(parsed("600000", state(true, false, false)), base).eventType());
        assertEquals(SecurityStatusEventType.EXCHANGE_CHANGE,
                classifier.classify(parsed("600000",
                        new SecurityStatusState(MarketExchange.SZSE, "MAIN", true, true, false)),
                        base).eventType());

        MaterializedSecurityEvent unlisted = event(
                SecurityStatusEventType.FULL_STATUS_SNAPSHOT,
                "600000", state(false, false, false), null);
        assertEquals(SecurityStatusEventType.LISTING,
                classifier.classify(parsed("600000", state(true, true, false)), unlisted)
                        .eventType());
        assertEquals(SecurityStatusEventType.DELISTING,
                classifier.classify(parsed("600000", state(false, false, false)), base)
                        .eventType());
    }

    @Test
    void classifierRejectsSymbolAndMultiFieldChanges() throws Exception {
        MaterializedSecurityEvent base = event(
                SecurityStatusEventType.FULL_STATUS_SNAPSHOT,
                "600000", state(true, true, false), null);
        assertTrue(classifier.classify(parsed("600001", state(true, true, false)), base)
                .unsupported());
        assertTrue(classifier.classify(parsed("600000",
                        new SecurityStatusState(MarketExchange.SZSE, "OTHER", true, true, false)),
                        base).unsupported());
    }

    @Test
    void normalizationOutcomeMappingIsExact() {
        assertEquals(IngestionModels.AttemptStatus.COMPLETED,
                NormalizationOutcome.EVENT_MATERIALIZED.requiredAttemptStatus());
        assertEquals(IngestionModels.AttemptStatus.COMPLETED,
                NormalizationOutcome.EVENT_REUSED.requiredAttemptStatus());
        assertEquals(IngestionModels.AttemptStatus.COMPLETED,
                NormalizationOutcome.NO_STATE_CHANGE.requiredAttemptStatus());
        assertEquals(IngestionModels.AttemptStatus.IDENTITY_UNRESOLVED,
                NormalizationOutcome.IDENTITY_UNRESOLVED.requiredAttemptStatus());
        assertEquals(IngestionModels.AttemptStatus.UNSUPPORTED_CONTRACT,
                NormalizationOutcome.UNSUPPORTED_CONTRACT.requiredAttemptStatus());
        assertEquals(IngestionModels.AttemptStatus.CONFLICT,
                NormalizationOutcome.CONFLICT.requiredAttemptStatus());
        assertEquals(IngestionModels.AttemptStatus.PROJECTION_FAILED,
                NormalizationOutcome.PROJECTION_FAILED.requiredAttemptStatus());
        assertEquals(IngestionModels.AttemptStatus.REJECTED,
                NormalizationOutcome.REJECTED.requiredAttemptStatus());
    }

    @Test
    void logicalIdentityHashesExcludePhysicalIdsAndSeparateNamespaces() {
        String identity = hasher.securityLogicalKey(
                RunNamespace.TEST, "TEST_AUTHORITY", "instrument-1",
                SecurityEventMaterializationModels.IDENTITY_CONTRACT_VERSION);
        assertEquals(identity, hasher.securityLogicalKey(
                RunNamespace.TEST, "TEST_AUTHORITY", "instrument-1",
                SecurityEventMaterializationModels.IDENTITY_CONTRACT_VERSION));
        assertNotEquals(identity, hasher.securityLogicalKey(
                RunNamespace.DEMO, "TEST_AUTHORITY", "instrument-1",
                SecurityEventMaterializationModels.IDENTITY_CONTRACT_VERSION));
        String mapping = hasher.mappingLogicalKey(
                RunNamespace.TEST, "SOURCE", "V1", "instrument-1",
                SecurityEventMaterializationModels.MAPPING_CONTRACT_VERSION);
        String event = hasher.eventLogicalKey("raw:v1:" + "a".repeat(64),
                SecurityStatusEventPayloadContract.VERSION,
                SecurityStatusEventType.FULL_STATUS_SNAPSHOT);
        assertTrue(identity.startsWith("security:v1:"));
        assertTrue(mapping.startsWith("mapping:v1:"));
        assertTrue(event.startsWith("event:v1:"));
    }

    @Test
    void resultAndLineageHashesChangeForBusinessLineageButNotPhysicalIds() {
        String result = hasher.normalizationResultHash(
                "attempt:v1:" + "1".repeat(64), NormalizationOutcome.EVENT_MATERIALIZED,
                "event:v1:" + "2".repeat(64), "security:v1:" + "3".repeat(64), null,
                SecurityEventMaterializationModels.NORMALIZER_VERSION,
                SecurityEventMaterializationModels.TRANSITION_RULE_VERSION,
                AssuranceLevel.RECONSTRUCTED_VERIFIED, null);
        assertNotEquals(result, hasher.normalizationResultHash(
                "attempt:v1:" + "1".repeat(64), NormalizationOutcome.EVENT_REUSED,
                "event:v1:" + "2".repeat(64), "security:v1:" + "3".repeat(64), null,
                SecurityEventMaterializationModels.NORMALIZER_VERSION,
                SecurityEventMaterializationModels.TRANSITION_RULE_VERSION,
                AssuranceLevel.RECONSTRUCTED_VERIFIED, null));
        String lineage = hasher.lineageHash(
                "event", "dataset", "raw", "run", "attempt", "mapping", "security", null,
                RunNamespace.TEST, SecurityStatusEventPayloadContract.VERSION,
                SecurityEventMaterializationModels.NORMALIZER_VERSION,
                SecurityEventMaterializationModels.TRANSITION_RULE_VERSION,
                "a".repeat(64), AssuranceLevel.RECONSTRUCTED_VERIFIED);
        assertNotEquals(lineage, hasher.lineageHash(
                "event", "dataset", "raw", "run", "attempt", "mapping-2", "security", null,
                RunNamespace.TEST, SecurityStatusEventPayloadContract.VERSION,
                SecurityEventMaterializationModels.NORMALIZER_VERSION,
                SecurityEventMaterializationModels.TRANSITION_RULE_VERSION,
                "a".repeat(64), AssuranceLevel.RECONSTRUCTED_VERIFIED));
    }

    @Test
    void assurancePropagationUsesTheFrozenConservativeOrderAndStageCeiling() {
        assertEquals(AssuranceLevel.RECONSTRUCTED_VERIFIED,
                SecurityEventMaterializationModels.conservative(
                        AssuranceLevel.PIT_VERIFIED,
                        AssuranceLevel.RECONSTRUCTED_VERIFIED));
        assertEquals(AssuranceLevel.INFERRED_RESEARCH,
                SecurityEventMaterializationModels.conservative(
                        AssuranceLevel.RECONSTRUCTED_VERIFIED,
                        AssuranceLevel.INFERRED_RESEARCH));
        assertEquals(AssuranceLevel.INFERRED_RESEARCH,
                SecurityEventMaterializationModels.conservative(
                        AssuranceLevel.INFERRED_RESEARCH,
                        AssuranceLevel.PIT_VERIFIED));
    }

    @Test
    void canonicalSecurityEventGoldenVectorsAreFrozen() {
        String identity = hasher.securityLogicalKey(
                RunNamespace.TEST, "TEST_AUTHORITY", "instrument-1",
                SecurityEventMaterializationModels.IDENTITY_CONTRACT_VERSION);
        String mapping = hasher.mappingLogicalKey(
                RunNamespace.TEST, "SOURCE", "source-v1", "instrument-1",
                SecurityEventMaterializationModels.MAPPING_CONTRACT_VERSION);
        String event = hasher.eventLogicalKey("raw:v1:" + "a".repeat(64),
                SecurityStatusEventPayloadContract.VERSION,
                SecurityStatusEventType.FULL_STATUS_SNAPSHOT);
        String result = hasher.normalizationResultHash(
                "attempt:v1:" + "b".repeat(64), NormalizationOutcome.EVENT_MATERIALIZED,
                event, identity, null, SecurityEventMaterializationModels.NORMALIZER_VERSION,
                SecurityEventMaterializationModels.TRANSITION_RULE_VERSION,
                AssuranceLevel.RECONSTRUCTED_VERIFIED, null);
        String lineage = hasher.lineageHash(
                event, "dataset:v1:" + "c".repeat(64), "raw:v1:" + "a".repeat(64),
                "run:v1:" + "d".repeat(64), "attempt:v1:" + "b".repeat(64), mapping,
                identity, null, RunNamespace.TEST, SecurityStatusEventPayloadContract.VERSION,
                SecurityEventMaterializationModels.NORMALIZER_VERSION,
                SecurityEventMaterializationModels.TRANSITION_RULE_VERSION,
                "e".repeat(64), AssuranceLevel.RECONSTRUCTED_VERIFIED);
        Fixture fixture = manifestFixture(11, 12);
        String manifest = hasher.manifestV2Hash(
                fixture.run(), fixture.dataset(), RunStatus.COMPLETED,
                1, 1, 1, 0, AssuranceLevel.RECONSTRUCTED_VERIFIED,
                List.of(fixture.entry()));

        assertAll(
                () -> assertEquals(
                        "security:v1:429d639f9e141c29e34fa2b2718ee7db53d86e1878e3f1979f9c346f9e58ffb4",
                        identity),
                () -> assertEquals(
                        "mapping:v1:ea96def660a1369c4d14f10ebe0009c0180dfd2481ff8538f575c40b9a1cc765",
                        mapping),
                () -> assertEquals(
                        "event:v1:621749331ab96f46b63d025ae3244526d55ce62f45aa4a95e1598f990c27a7d2",
                        event),
                () -> assertEquals(
                        "4c9260a4ff827d886dfbe3ba5a5aa13d689b7ba46df84e31f26fc0075e8a25a4",
                        result),
                () -> assertEquals(
                        "f721031cdf601739e25cb290cca72be11e66b88559a8693676293af54fdcd87a",
                        lineage),
                () -> assertEquals(
                        "cf8105f49631c1ab7c586bceda7595553de6fa84fb8b9da13e79579c584b343c",
                        manifest));
    }

    @Test
    void manifestV2RejectsDuplicateSemanticEntriesAndIgnoresOrderAndPhysicalIds() {
        Fixture first = manifestFixture(11, 12);
        Fixture sameBusinessDifferentPhysicalIds = manifestFixture(901, 902);
        assertEquals(hasher.manifestV2Hash(
                        first.run(), first.dataset(), RunStatus.COMPLETED,
                        1, 1, 1, 0, AssuranceLevel.RECONSTRUCTED_VERIFIED,
                        List.of(first.entry())),
                hasher.manifestV2Hash(
                        sameBusinessDifferentPhysicalIds.run(),
                        sameBusinessDifferentPhysicalIds.dataset(), RunStatus.COMPLETED,
                        1, 1, 1, 0, AssuranceLevel.RECONSTRUCTED_VERIFIED,
                        List.of(sameBusinessDifferentPhysicalIds.entry())));

        SecurityEventManifestEntry second = manifestEntry(
                "raw:v1:" + "0".repeat(64), "attempt:v1:" + "1".repeat(64), 2);
        assertEquals(hasher.manifestV2Hash(
                        first.run(), first.dataset(), RunStatus.COMPLETED,
                        1, 1, 1, 0, AssuranceLevel.RECONSTRUCTED_VERIFIED,
                        List.of(first.entry(), second)),
                hasher.manifestV2Hash(
                        first.run(), first.dataset(), RunStatus.COMPLETED,
                        1, 1, 1, 0, AssuranceLevel.RECONSTRUCTED_VERIFIED,
                        List.of(second, first.entry())));
        assertThrows(IllegalArgumentException.class, () -> hasher.manifestV2Hash(
                first.run(), first.dataset(), RunStatus.COMPLETED,
                1, 1, 1, 0, AssuranceLevel.RECONSTRUCTED_VERIFIED,
                List.of(first.entry(), first.entry())));
    }

    private ParsedSecurityStatusRaw parsed(String symbol, SecurityStatusState state) throws Exception {
        return parser.parse(raw(symbol, state));
    }

    private com.fasterxml.jackson.databind.JsonNode raw(String symbol, SecurityStatusState state)
            throws Exception {
        return mapper.readTree("""
                {"schemaVersion":"SECURITY_STATUS_RAW_TEST_V1","symbol":"%s",
                 "state":{"exchange":"%s","board":"%s","listed":%s,
                          "active":%s,"isSt":%s}}
                """.formatted(symbol, state.exchange(), state.board(), state.listed(),
                state.active(), state.st()));
    }

    private static SecurityStatusState state(boolean listed, boolean active, boolean st) {
        return new SecurityStatusState(MarketExchange.SSE, "MAIN", listed, active, st);
    }

    private static MaterializedSecurityEvent event(
            SecurityStatusEventType type,
            String symbol,
            SecurityStatusState state,
            MaterializedSecurityEvent predecessor
    ) {
        var payload = SecurityStatusEventPayloadContract.payload(state);
        return new MaterializedSecurityEvent(
                predecessor == null ? 1 : 2, 1, symbol, type,
                predecessor == null ? LocalDate.of(2025, 1, 1) : LocalDate.of(2025, 2, 1),
                null, Instant.parse("2025-01-01T00:00:00Z"),
                Instant.parse("2025-01-01T00:00:00Z"),
                Instant.parse("2025-01-01T00:00:01Z"), "SOURCE", "V1", "record", "1",
                TemporalTrustLevel.OBSERVED, payload,
                SecurityStatusEventPayloadContract.hash(payload),
                predecessor == null ? null : predecessor.id(),
                "event:v1:" + "a".repeat(64), SecurityStatusEventPayloadContract.VERSION,
                RunNamespace.TEST, AssuranceLevel.RECONSTRUCTED_VERIFIED,
                "security:v1:" + "b".repeat(64));
    }

    private Fixture manifestFixture(long runId, long datasetId) {
        Instant instant = Instant.parse("2025-01-01T01:02:03.123456Z");
        DatasetVersion dataset = new DatasetVersion(
                datasetId, "SECURITY_STATUS", "SOURCE", "source-v1", "connector-v1",
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), instant, instant,
                "f".repeat(64), TemporalTrustLevel.OBSERVED, mapper.createObjectNode());
        IngestionRun run = new IngestionRun(
                runId, "run:v1:" + "d".repeat(64), datasetId,
                "dataset:v1:" + "c".repeat(64), DatasetType.SECURITY_STATUS,
                RunNamespace.TEST, OperationType.INGEST, "request-alpha", null,
                "root-request:v1:" + "9".repeat(64), 1,
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31),
                ManifestContractVersion.INGESTION_MANIFEST_V2_SECURITY_EVENT,
                RunStatus.RUNNING, instant, null, null, null,
                null, null, null, null, null, instant);
        return new Fixture(run, dataset, manifestEntry(
                "raw:v1:" + "a".repeat(64), "attempt:v1:" + "b".repeat(64), 1));
    }

    private static SecurityEventManifestEntry manifestEntry(
            String rawLogicalKey,
            String attemptLogicalKey,
            int attemptNo
    ) {
        return new SecurityEventManifestEntry(
                rawLogicalKey, "1".repeat(64), TemporalTrustLevel.OBSERVED,
                "instrument-1", attemptNo, attemptLogicalKey,
                IngestionModels.AttemptStatus.COMPLETED, "processor-v1",
                SecurityEventMaterializationModels.RAW_CONTRACT_VERSION,
                IngestionModels.PublicationTimeVerification.VERIFIED,
                AssuranceLevel.RECONSTRUCTED_VERIFIED,
                KnowledgeTimePolicyV1.VERSION,
                AssuranceLevel.RECONSTRUCTED_VERIFIED,
                Instant.parse("2025-01-01T01:02:03.123456Z"), null, "2".repeat(64),
                NormalizationOutcome.EVENT_MATERIALIZED, "event:v1:" + "3".repeat(64),
                SecurityStatusEventType.FULL_STATUS_SNAPSHOT, "4".repeat(64),
                AssuranceLevel.RECONSTRUCTED_VERIFIED,
                "security:v1:" + "5".repeat(64), null, RunNamespace.TEST,
                SecurityEventMaterializationModels.NORMALIZER_VERSION,
                SecurityEventMaterializationModels.TRANSITION_RULE_VERSION,
                AssuranceLevel.RECONSTRUCTED_VERIFIED, "6".repeat(64), "7".repeat(64));
    }

    private record Fixture(
            IngestionRun run,
            DatasetVersion dataset,
            SecurityEventManifestEntry entry
    ) {}
}

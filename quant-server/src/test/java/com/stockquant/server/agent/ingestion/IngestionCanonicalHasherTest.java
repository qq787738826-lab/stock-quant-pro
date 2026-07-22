package com.stockquant.server.agent.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.ingestion.IngestionModels.AssuranceLevel;
import com.stockquant.server.agent.ingestion.IngestionModels.AttemptStatus;
import com.stockquant.server.agent.ingestion.IngestionModels.DatasetType;
import com.stockquant.server.agent.ingestion.IngestionModels.IngestionRun;
import com.stockquant.server.agent.ingestion.IngestionModels.ManifestEntry;
import com.stockquant.server.agent.ingestion.IngestionModels.OperationType;
import com.stockquant.server.agent.ingestion.IngestionModels.ProcessingAttempt;
import com.stockquant.server.agent.ingestion.IngestionModels.PublicationTimeVerification;
import com.stockquant.server.agent.ingestion.IngestionModels.RawRecord;
import com.stockquant.server.agent.ingestion.IngestionModels.RunNamespace;
import com.stockquant.server.agent.ingestion.IngestionModels.RunStatus;
import com.stockquant.server.agent.temporal.TemporalModels.DatasetVersion;
import com.stockquant.server.agent.temporal.TemporalTrustLevel;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IngestionCanonicalHasherTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final IngestionCanonicalHasher hasher = new IngestionCanonicalHasher();

    @Test
    void hashesStableLogicalIdentityWithoutPhysicalOrRecordingMetadata() throws Exception {
        DatasetVersion first = dataset(
                1, Instant.parse("2025-01-01T01:02:03.123456789Z"),
                Instant.parse("2025-01-01T02:00:00Z"), TemporalTrustLevel.OBSERVED,
                mapper.readTree("{\"batch\":1}"));
        DatasetVersion replay = dataset(
                99, Instant.parse("2025-02-01T00:00:00Z"),
                Instant.parse("2025-02-02T00:00:00Z"),
                TemporalTrustLevel.BACKFILLED_INFERRED,
                mapper.readTree("{\"technicalRetry\":true}"));

        assertEquals(hasher.datasetLogicalKey(first), hasher.datasetLogicalKey(replay));
        String firstRoot = hasher.rootRequestLogicalKey(
                hasher.datasetLogicalKey(first), DatasetType.SECURITY_STATUS,
                RunNamespace.TEST, OperationType.INGEST, "request-1",
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));
        String replayRoot = hasher.rootRequestLogicalKey(
                hasher.datasetLogicalKey(replay), DatasetType.SECURITY_STATUS,
                RunNamespace.TEST, OperationType.INGEST, "request-1",
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));
        assertEquals(firstRoot, replayRoot);
        assertEquals(hasher.runLogicalKey(
                        hasher.datasetLogicalKey(first), DatasetType.SECURITY_STATUS,
                        RunNamespace.TEST, OperationType.INGEST, "request-1", firstRoot, 1,
                        LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), null),
                hasher.runLogicalKey(
                        hasher.datasetLogicalKey(replay), DatasetType.SECURITY_STATUS,
                        RunNamespace.TEST, OperationType.INGEST, "request-1", replayRoot, 1,
                        LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), null));
    }

    @Test
    void canonicalJsonSortsObjectFieldsButPreservesArrayOrder() throws Exception {
        assertEquals(
                hasher.jsonHash(mapper.readTree("{\"b\":1.00,\"a\":true}")),
                hasher.jsonHash(mapper.readTree("{\"a\":true,\"b\":1}")));
        assertNotEquals(
                hasher.jsonHash(mapper.readTree("[1,2]")),
                hasher.jsonHash(mapper.readTree("[2,1]")));
        assertNotEquals(
                hasher.jsonHash(mapper.readTree("null")),
                hasher.jsonHash(mapper.readTree("\"\"")));
        assertEquals(
                hasher.jsonHash(mapper.readTree("{\"\":1,\"𐀀\":2}")),
                hasher.jsonHash(mapper.readTree("{\"𐀀\":2,\"\":1}")));
    }

    @Test
    void manifestRejectsDuplicateSemanticSetMembersAndIsInputOrderIndependent() {
        IngestionRun run = run();
        DatasetVersion dataset = dataset(
                1, Instant.parse("2025-01-01T00:00:00Z"),
                Instant.parse("2025-01-01T00:00:00Z"), TemporalTrustLevel.OBSERVED,
                mapper.createObjectNode());
        ManifestEntry a = entry("raw-a", "attempt-a", "a");
        ManifestEntry b = entry("raw-b", "attempt-b", "b");

        assertEquals(
                hasher.manifestHash(run, dataset, RunStatus.COMPLETED, 2, 2, 2, 0,
                        AssuranceLevel.RECONSTRUCTED_VERIFIED, List.of(a, b)),
                hasher.manifestHash(run, dataset, RunStatus.COMPLETED, 2, 2, 2, 0,
                        AssuranceLevel.RECONSTRUCTED_VERIFIED, List.of(b, a)));
        assertNotEquals(
                hasher.manifestHash(run, dataset, RunStatus.COMPLETED, 2, 2, 2, 0,
                        AssuranceLevel.RECONSTRUCTED_VERIFIED, List.of(a, b)),
                hasher.manifestHash(run, dataset, RunStatus.FAILED, 2, 2, 2, 0,
                        AssuranceLevel.RECONSTRUCTED_VERIFIED, List.of(a, b)));
        assertThrows(IllegalArgumentException.class, () -> hasher.manifestHash(
                run, dataset, RunStatus.COMPLETED, 2, 2, 2, 0,
                AssuranceLevel.RECONSTRUCTED_VERIFIED,
                List.of(a, a)));
        assertThrows(IllegalArgumentException.class, () -> hasher.manifestHash(
                run, dataset, RunStatus.COMPLETED, 2, 2, 2, 0,
                AssuranceLevel.RECONSTRUCTED_VERIFIED,
                List.of(a, entry("raw-c", "attempt-a", "c"))));
    }

    @Test
    void namespaceAndRevisionRemainPartOfRawIdentity() {
        String test = hasher.rawRecordLogicalKey(
                DatasetType.SECURITY_STATUS, RunNamespace.TEST,
                "SOURCE", "V1", "record", "1");
        assertNotEquals(test, hasher.rawRecordLogicalKey(
                DatasetType.SECURITY_STATUS, RunNamespace.DEMO,
                "SOURCE", "V1", "record", "1"));
        assertNotEquals(test, hasher.rawRecordLogicalKey(
                DatasetType.SECURITY_STATUS, RunNamespace.TEST,
                "SOURCE", "V1", "record", "2"));
    }

    @Test
    void attemptNumberIsPartOfAttemptIdentity() {
        String first = hasher.attemptLogicalKey(
                "run", "raw", 1, "processor", "contract");
        assertNotEquals(first, hasher.attemptLogicalKey(
                "run", "raw", 2, "processor", "contract"));
    }

    @Test
    void completedAtIsAuditOnlyAndDoesNotChangeManifestHash() {
        RawRecord raw = raw();
        ProcessingAttempt first = attempt(Instant.parse("2025-01-01T00:00:01Z"));
        ProcessingAttempt later = attempt(Instant.parse("2025-01-01T00:00:09Z"));

        assertEquals(ManifestEntry.from(raw, first), ManifestEntry.from(raw, later));
        assertEquals(
                hasher.manifestHash(run(), dataset(1, Instant.parse("2025-01-01T00:00:00Z"),
                                Instant.parse("2025-01-01T00:00:00Z"),
                                TemporalTrustLevel.OBSERVED, mapper.createObjectNode()),
                        RunStatus.COMPLETED, 1, 1, 1, 0,
                        AssuranceLevel.RECONSTRUCTED_VERIFIED,
                        List.of(ManifestEntry.from(raw, first))),
                hasher.manifestHash(run(), dataset(1, Instant.parse("2025-01-01T00:00:00Z"),
                                Instant.parse("2025-01-01T00:00:00Z"),
                                TemporalTrustLevel.OBSERVED, mapper.createObjectNode()),
                        RunStatus.COMPLETED, 1, 1, 1, 0,
                        AssuranceLevel.RECONSTRUCTED_VERIFIED,
                        List.of(ManifestEntry.from(raw, later))));
    }

    @Test
    void canonicalV1GoldenVectorsAreFrozen() throws Exception {
        DatasetVersion dataset = dataset(
                1, Instant.parse("2025-01-01T01:02:03.123456789Z"),
                Instant.parse("2025-01-01T02:00:00Z"), TemporalTrustLevel.OBSERVED,
                mapper.readTree("{\"batch\":1}"));
        String datasetKey = hasher.datasetLogicalKey(dataset);
        String rootKey = hasher.rootRequestLogicalKey(
                datasetKey, DatasetType.SECURITY_STATUS, RunNamespace.TEST,
                OperationType.INGEST, "request-1",
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));
        String runKey = hasher.runLogicalKey(
                datasetKey, DatasetType.SECURITY_STATUS, RunNamespace.TEST,
                OperationType.INGEST, "request-1", rootKey, 1,
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), null);
        String rawKey = hasher.rawRecordLogicalKey(
                DatasetType.SECURITY_STATUS, RunNamespace.TEST,
                "SOURCE", "V1", "record", "1");

        assertEquals("dataset:v1:1bdc3835611398f211f40274f8714b7333d8b06b9febef303be5109dafcca32c",
                datasetKey);
        assertEquals("root-request:v1:bd6fc3b681f2653fc27f625bb84a1e589a30998f6fc9424f2450d6b2f8e1ed01",
                rootKey);
        assertEquals("run:v1:0872277cc21e8c6193cc8445c476be7e7b540262e065e69e6bce62bb2d3609bc",
                runKey);
        assertEquals("raw:v1:1e8ee05be8508af23e7fb8f682ea562eaa9e70965c9de16d852c284929738391",
                rawKey);
        assertEquals("attempt:v1:2583c785c63ac8edfe08a671d8f50ebb45584f957f74a7cc8eba4c72de0e8bb0",
                hasher.attemptLogicalKey(runKey, rawKey, 1, "processor", "contract"));
        assertEquals("3e0be30d9c55a4c203bff3bcdce0842e1cd7054bf46f9c24f476e51adf2cf34b",
                hasher.jsonHash(mapper.createObjectNode()));
        assertEquals("88056dfd66a92248cec08dc9e4bd84917809f3b2fbcb94183f9049f15180ebff",
                hasher.manifestHash(
                        run(), dataset, RunStatus.COMPLETED, 1, 1, 1, 0,
                        AssuranceLevel.RECONSTRUCTED_VERIFIED,
                        List.of(entry("raw-a", "attempt-a", "a"))));
    }

    private DatasetVersion dataset(
            long id,
            Instant fetched,
            Instant recorded,
            TemporalTrustLevel trust,
            com.fasterxml.jackson.databind.JsonNode metadata
    ) {
        return new DatasetVersion(
                id, "SECURITY_STATUS", "SOURCE", "SOURCE_V1", "CONNECTOR_V1",
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), fetched, recorded,
                "a".repeat(64), trust, metadata);
    }

    private IngestionRun run() {
        Instant time = Instant.parse("2025-01-01T00:00:00Z");
        return new IngestionRun(
                1, "run:v1:" + "1".repeat(64), 1,
                "dataset:v1:" + "2".repeat(64), DatasetType.SECURITY_STATUS,
                RunNamespace.TEST, OperationType.INGEST, "request", null,
                "root-request:v1:" + "3".repeat(64), 1,
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), RunStatus.RUNNING,
                time, null, null, null, null, null, null, null, null, time);
    }

    private ManifestEntry entry(String raw, String attempt, String suffix) {
        return new ManifestEntry(
                raw, suffix.repeat(64), TemporalTrustLevel.OBSERVED,
                "instrument-" + suffix, null, null, 1,
                attempt, AttemptStatus.COMPLETED, "processor-v1", "contract-v1",
                IngestionModels.PublicationTimeVerification.VERIFIED,
                KnowledgeTimePolicyV1.VERSION,
                AssuranceLevel.RECONSTRUCTED_VERIFIED,
                Instant.parse("2025-01-01T00:00:00.123456789Z"), null,
                suffix.repeat(64));
    }

    private RawRecord raw() {
        return new RawRecord(
                1, DatasetType.SECURITY_STATUS, 1, 1, "raw-a", RunNamespace.TEST,
                "SOURCE", "V1", "record", "1", "instrument-a", null, null,
                Instant.parse("2025-01-01T00:00:00Z"), LocalDate.of(2025, 1, 1), null,
                Instant.parse("2025-01-01T00:00:00Z"),
                Instant.parse("2025-01-01T00:00:00Z"), TemporalTrustLevel.OBSERVED,
                mapper.createObjectNode(),
                "3e0be30d9c55a4c203bff3bcdce0842e1cd7054bf46f9c24f476e51adf2cf34b");
    }

    private ProcessingAttempt attempt(Instant completedAt) {
        return new ProcessingAttempt(
                1, DatasetType.SECURITY_STATUS, 1, 1, 1, "attempt-a",
                AttemptStatus.COMPLETED, "processor-v1", "contract-v1",
                PublicationTimeVerification.VERIFIED,
                Instant.parse("2025-01-01T00:00:00Z"), KnowledgeTimePolicyV1.VERSION,
                AssuranceLevel.RECONSTRUCTED_VERIFIED, null, mapper.createObjectNode(),
                "3e0be30d9c55a4c203bff3bcdce0842e1cd7054bf46f9c24f476e51adf2cf34b",
                completedAt, completedAt);
    }
}

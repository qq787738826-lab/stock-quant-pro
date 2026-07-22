package com.stockquant.server.agent.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.ingestion.IngestionModels.AssuranceLevel;
import com.stockquant.server.agent.ingestion.IngestionModels.AppendTradingCalendarRawCommand;
import com.stockquant.server.agent.ingestion.IngestionModels.AttemptStatus;
import com.stockquant.server.agent.ingestion.IngestionModels.DatasetType;
import com.stockquant.server.agent.ingestion.IngestionModels.IngestionRun;
import com.stockquant.server.agent.ingestion.IngestionModels.OperationType;
import com.stockquant.server.agent.ingestion.IngestionModels.ProcessingAttempt;
import com.stockquant.server.agent.ingestion.IngestionModels.PublicationTimeVerification;
import com.stockquant.server.agent.ingestion.IngestionModels.RawRecord;
import com.stockquant.server.agent.ingestion.IngestionModels.RecordAttemptCommand;
import com.stockquant.server.agent.ingestion.IngestionModels.RunNamespace;
import com.stockquant.server.agent.ingestion.IngestionModels.RunStatus;
import com.stockquant.server.agent.ingestion.IngestionModels.SealRunCommand;
import com.stockquant.server.agent.ingestion.IngestionModels.StartRunCommand;
import com.stockquant.server.agent.temporal.MarketDataDatasetVersionRepository;
import com.stockquant.server.agent.temporal.TemporalModels.DatasetVersion;
import com.stockquant.server.agent.temporal.TemporalTrustLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MarketDataIngestionServiceTest {

    private static final Instant OBSERVED = Instant.parse("2025-01-02T00:00:00Z");
    private static final Instant NOW = Instant.parse("2025-01-02T00:00:01Z");

    private MarketDataIngestionRepository repository;
    private MarketDataDatasetVersionRepository datasets;
    private IngestionCanonicalHasher hasher;
    private MarketDataIngestionService service;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        repository = mock(MarketDataIngestionRepository.class);
        datasets = mock(MarketDataDatasetVersionRepository.class);
        hasher = new IngestionCanonicalHasher();
        mapper = new ObjectMapper();
        service = new MarketDataIngestionService(
                repository, datasets, hasher, new KnowledgeTimePolicyV1(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void sourceNeutralStageRejectsFormalBeforeDatabaseAccess() {
        StartRunCommand command = new StartRunCommand(
                1, DatasetType.SECURITY_STATUS, RunNamespace.FORMAL,
                OperationType.INGEST, "formal", LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 12, 31), null);

        assertThrows(IngestionDataConflictException.class, () -> service.startRun(command));
        verify(datasets, never()).findById(anyLong());
        verifyNoInteractions(repository);
    }

    @Test
    void sourceNeutralAttemptMetadataMustBeEmpty() {
        assertThrows(IllegalArgumentException.class, () -> new RecordAttemptCommand(
                1, 2, 1, AttemptStatus.COMPLETED, "processor-v1", "contract-v1",
                PublicationTimeVerification.VERIFIED, AssuranceLevel.PIT_VERIFIED,
                null, mapper.createObjectNode().put("projectedEventId", 99)));
    }

    @Test
    void calendarRawRequiresWhitelistedExchangeAndTradeDate() {
        var payload = mapper.createObjectNode();
        String payloadHash = hasher.jsonHash(payload);
        assertThrows(IllegalArgumentException.class, () -> new AppendTradingCalendarRawCommand(
                1, "SOURCE", "V1", "record", "1", "BSE",
                LocalDate.of(2025, 1, 2), null, LocalDate.of(2025, 1, 2), null,
                TemporalTrustLevel.OBSERVED, payload, payloadHash));
        assertThrows(NullPointerException.class, () -> new AppendTradingCalendarRawCommand(
                1, "SOURCE", "V1", "record", "1", "SSE", null,
                null, LocalDate.of(2025, 1, 2), null,
                TemporalTrustLevel.OBSERVED, payload, payloadHash));
    }

    @Test
    void identityUnresolvedIsTerminalAndRequiresAnErrorCode() {
        var empty = mapper.createObjectNode();
        RecordAttemptCommand value = new RecordAttemptCommand(
                1, 2, 1, AttemptStatus.IDENTITY_UNRESOLVED, "processor-v1", "contract-v1",
                PublicationTimeVerification.NOT_PROVIDED, AssuranceLevel.INFERRED_RESEARCH,
                "IDENTITY_NOT_MAPPED", empty);
        assertEquals(AttemptStatus.IDENTITY_UNRESOLVED, value.status());
        assertThrows(IllegalArgumentException.class, () -> new RecordAttemptCommand(
                1, 2, 1, AttemptStatus.IDENTITY_UNRESOLVED, "processor-v1", "contract-v1",
                PublicationTimeVerification.NOT_PROVIDED, AssuranceLevel.INFERRED_RESEARCH,
                null, empty));
    }

    @Test
    void retryCommandRequiresParentAndNonRetryRejectsParent() {
        assertThrows(IllegalArgumentException.class, () -> new StartRunCommand(
                1, DatasetType.SECURITY_STATUS, RunNamespace.TEST, OperationType.RETRY,
                "request", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 2), null));
        assertThrows(IllegalArgumentException.class, () -> new StartRunCommand(
                1, DatasetType.SECURITY_STATUS, RunNamespace.TEST, OperationType.INGEST,
                "request", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 2),
                "run:v1:" + "a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new StartRunCommand(
                1, DatasetType.SECURITY_STATUS, RunNamespace.TEST, OperationType.INGEST,
                "request", LocalDate.of(2025, 1, 2), LocalDate.of(2025, 1, 1), null));
    }

    @Test
    void sourceNeutralAttemptCapsRequestedPitAtReconstructed() {
        IngestionRun run = run();
        RawRecord raw = raw();
        var result = mapper.createObjectNode();
        String resultHash = hasher.jsonHash(result);
        String logicalKey = hasher.attemptLogicalKey(
                run.logicalKey(), raw.logicalKey(), 1, "processor-v1", "contract-v1");
        ProcessingAttempt persisted = new ProcessingAttempt(
                3, DatasetType.SECURITY_STATUS, run.id(), raw.id(), 1, logicalKey,
                AttemptStatus.COMPLETED, "processor-v1", "contract-v1",
                PublicationTimeVerification.VERIFIED, AssuranceLevel.PIT_VERIFIED,
                raw.sourcePublishedAt(),
                KnowledgeTimePolicyV1.VERSION, AssuranceLevel.RECONSTRUCTED_VERIFIED,
                null, result, resultHash, OBSERVED, NOW);
        when(repository.lockRun(run.id())).thenReturn(Optional.of(run));
        when(repository.findRawRecord(DatasetType.SECURITY_STATUS, raw.id()))
                .thenReturn(Optional.of(raw));
        when(repository.isRawRecordAttachedToRun(DatasetType.SECURITY_STATUS, run.id(), raw.id()))
                .thenReturn(true);
        when(datasets.findById(run.datasetVersionId())).thenReturn(Optional.of(dataset()));
        when(repository.insertAttempt(
                eq(DatasetType.SECURITY_STATUS), eq(run.id()), eq(raw.id()), eq(1), eq(logicalKey),
                eq(AttemptStatus.COMPLETED), eq("processor-v1"), eq("contract-v1"),
                eq(PublicationTimeVerification.VERIFIED), eq(AssuranceLevel.PIT_VERIFIED),
                eq(raw.sourcePublishedAt()),
                eq(AssuranceLevel.RECONSTRUCTED_VERIFIED), eq(null), eq(result),
                eq(resultHash))).thenReturn(Optional.of(persisted));

        ProcessingAttempt value = service.recordSecurityStatusAttempt(new RecordAttemptCommand(
                run.id(), raw.id(), 1, AttemptStatus.COMPLETED, "processor-v1", "contract-v1",
                PublicationTimeVerification.VERIFIED, AssuranceLevel.PIT_VERIFIED,
                null, result));

        assertEquals(AssuranceLevel.RECONSTRUCTED_VERIFIED, value.assuranceLevel());
        assertEquals(raw.sourcePublishedAt(), value.derivedKnownFrom());
    }

    @Test
    void datasetTrustParticipatesInConservativeAssurance() {
        IngestionRun run = run();
        RawRecord raw = raw();
        DatasetVersion inferredDataset = dataset(TemporalTrustLevel.BACKFILLED_INFERRED);
        var result = mapper.createObjectNode();
        String resultHash = hasher.jsonHash(result);
        String logicalKey = hasher.attemptLogicalKey(
                run.logicalKey(), raw.logicalKey(), 1, "processor-v1", "contract-v1");
        ProcessingAttempt persisted = new ProcessingAttempt(
                3, DatasetType.SECURITY_STATUS, run.id(), raw.id(), 1, logicalKey,
                AttemptStatus.COMPLETED, "processor-v1", "contract-v1",
                PublicationTimeVerification.VERIFIED, AssuranceLevel.PIT_VERIFIED,
                raw.sourcePublishedAt(),
                KnowledgeTimePolicyV1.VERSION, AssuranceLevel.INFERRED_RESEARCH,
                null, result, resultHash, OBSERVED, NOW);
        when(repository.lockRun(run.id())).thenReturn(Optional.of(run));
        when(repository.findRawRecord(DatasetType.SECURITY_STATUS, raw.id()))
                .thenReturn(Optional.of(raw));
        when(repository.isRawRecordAttachedToRun(DatasetType.SECURITY_STATUS, run.id(), raw.id()))
                .thenReturn(true);
        when(datasets.findById(run.datasetVersionId())).thenReturn(Optional.of(inferredDataset));
        when(repository.insertAttempt(
                eq(DatasetType.SECURITY_STATUS), eq(run.id()), eq(raw.id()), eq(1), eq(logicalKey),
                eq(AttemptStatus.COMPLETED), eq("processor-v1"), eq("contract-v1"),
                eq(PublicationTimeVerification.VERIFIED), eq(AssuranceLevel.PIT_VERIFIED),
                eq(raw.sourcePublishedAt()),
                eq(AssuranceLevel.INFERRED_RESEARCH), eq(null), eq(result), eq(resultHash)))
                .thenReturn(Optional.of(persisted));

        ProcessingAttempt value = service.recordSecurityStatusAttempt(new RecordAttemptCommand(
                run.id(), raw.id(), 1, AttemptStatus.COMPLETED, "processor-v1", "contract-v1",
                PublicationTimeVerification.VERIFIED, AssuranceLevel.PIT_VERIFIED,
                null, result));

        assertEquals(AssuranceLevel.INFERRED_RESEARCH, value.assuranceLevel());
    }

    @Test
    void idempotentAttemptRejectsDifferentRequestedAssurance() {
        IngestionRun run = run();
        RawRecord raw = raw();
        var result = mapper.createObjectNode();
        String resultHash = hasher.jsonHash(result);
        String logicalKey = hasher.attemptLogicalKey(
                run.logicalKey(), raw.logicalKey(), 1, "processor-v1", "contract-v1");
        ProcessingAttempt persisted = new ProcessingAttempt(
                3, DatasetType.SECURITY_STATUS, run.id(), raw.id(), 1, logicalKey,
                AttemptStatus.COMPLETED, "processor-v1", "contract-v1",
                PublicationTimeVerification.VERIFIED, AssuranceLevel.PIT_VERIFIED,
                raw.sourcePublishedAt(), KnowledgeTimePolicyV1.VERSION,
                AssuranceLevel.RECONSTRUCTED_VERIFIED, null, result, resultHash, OBSERVED, NOW);
        when(repository.lockRun(run.id())).thenReturn(Optional.of(run));
        when(repository.findRawRecord(DatasetType.SECURITY_STATUS, raw.id()))
                .thenReturn(Optional.of(raw));
        when(repository.isRawRecordAttachedToRun(DatasetType.SECURITY_STATUS, run.id(), raw.id()))
                .thenReturn(true);
        when(datasets.findById(run.datasetVersionId())).thenReturn(Optional.of(dataset()));
        when(repository.insertAttempt(
                eq(DatasetType.SECURITY_STATUS), eq(run.id()), eq(raw.id()), eq(1), eq(logicalKey),
                eq(AttemptStatus.COMPLETED), eq("processor-v1"), eq("contract-v1"),
                eq(PublicationTimeVerification.VERIFIED), eq(AssuranceLevel.INFERRED_RESEARCH),
                eq(raw.sourcePublishedAt()), eq(AssuranceLevel.INFERRED_RESEARCH), eq(null),
                eq(result), eq(resultHash))).thenReturn(Optional.empty());
        when(repository.findAttempt(DatasetType.SECURITY_STATUS, run.id(), raw.id(), 1))
                .thenReturn(Optional.of(persisted));

        assertThrows(IngestionDataConflictException.class, () ->
                service.recordSecurityStatusAttempt(new RecordAttemptCommand(
                        run.id(), raw.id(), 1, AttemptStatus.COMPLETED,
                        "processor-v1", "contract-v1", PublicationTimeVerification.VERIFIED,
                        AssuranceLevel.INFERRED_RESEARCH, null, result)));
    }

    @Test
    void runCannotSealWhileReceivedRawLacksTerminalAttempt() {
        IngestionRun run = run();
        DatasetVersion dataset = dataset();
        when(repository.lockRun(run.id())).thenReturn(Optional.of(run));
        when(datasets.findById(dataset.id())).thenReturn(Optional.of(dataset));
        when(repository.findManifestEntries(DatasetType.SECURITY_STATUS, run.id()))
                .thenReturn(List.of());
        when(repository.findFinalAttempts(DatasetType.SECURITY_STATUS, run.id()))
                .thenReturn(List.of());
        when(repository.countRunRawRecords(DatasetType.SECURITY_STATUS, run.id()))
                .thenReturn(1);

        assertThrows(IngestionDataConflictException.class, () -> service.sealRun(
                new SealRunCommand(run.id(), RunStatus.PARTIAL, 1)));
        verify(repository, never()).sealRun(
                anyLong(), any(), anyString(), anyInt(), anyInt(), anyInt(),
                anyInt(), any());
    }

    private IngestionRun run() {
        return new IngestionRun(
                1, "run:v1:" + "1".repeat(64), 1,
                "dataset:v1:" + "2".repeat(64), DatasetType.SECURITY_STATUS,
                RunNamespace.TEST, OperationType.INGEST, "request", null,
                "root-request:v1:" + "4".repeat(64), 1,
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), RunStatus.RUNNING,
                OBSERVED, null, null, null, null, null, null, null, null, OBSERVED);
    }

    private RawRecord raw() {
        var payload = mapper.createObjectNode().put("record", "one");
        return new RawRecord(
                2, DatasetType.SECURITY_STATUS, 1, 1,
                "raw:v1:" + "3".repeat(64), RunNamespace.TEST,
                "SOURCE", "SOURCE_V1", "record-1", "1", "instrument-1", null, null,
                OBSERVED.minusSeconds(60), LocalDate.of(2025, 1, 2), null,
                OBSERVED, OBSERVED, TemporalTrustLevel.OBSERVED,
                payload, hasher.jsonHash(payload));
    }

    private DatasetVersion dataset() {
        return dataset(TemporalTrustLevel.OBSERVED);
    }

    private DatasetVersion dataset(TemporalTrustLevel trustLevel) {
        return new DatasetVersion(
                1, "SECURITY_STATUS", "SOURCE", "SOURCE_V1", "CONNECTOR_V1",
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31),
                OBSERVED, OBSERVED, "a".repeat(64), trustLevel,
                mapper.createObjectNode());
    }
}

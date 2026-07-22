package com.stockquant.server.agent.ingestion;

import com.stockquant.server.agent.ingestion.IngestionModels.AppendRawRecordCommand;
import com.stockquant.server.agent.ingestion.IngestionModels.AppendSecurityStatusRawCommand;
import com.stockquant.server.agent.ingestion.IngestionModels.AppendTradingCalendarRawCommand;
import com.stockquant.server.agent.ingestion.IngestionModels.AssuranceLevel;
import com.stockquant.server.agent.ingestion.IngestionModels.AttemptStatus;
import com.stockquant.server.agent.ingestion.IngestionModels.DatasetType;
import com.stockquant.server.agent.ingestion.IngestionModels.IngestionRun;
import com.stockquant.server.agent.ingestion.IngestionModels.ManifestEntry;
import com.stockquant.server.agent.ingestion.IngestionModels.ProcessingAttempt;
import com.stockquant.server.agent.ingestion.IngestionModels.RawRecord;
import com.stockquant.server.agent.ingestion.IngestionModels.RecordAttemptCommand;
import com.stockquant.server.agent.ingestion.IngestionModels.RunStatus;
import com.stockquant.server.agent.ingestion.IngestionModels.SealRunCommand;
import com.stockquant.server.agent.ingestion.IngestionModels.StartRunCommand;
import com.stockquant.server.agent.temporal.MarketDataDatasetVersionRepository;
import com.stockquant.server.agent.temporal.TemporalModels.DatasetVersion;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Source-neutral ingestion lifecycle. No source adapter, state projection, calendar publication or
 * Universe generation is performed here.
 */
@Service
public class MarketDataIngestionService {

    private final MarketDataIngestionRepository repository;
    private final MarketDataDatasetVersionRepository datasets;
    private final IngestionCanonicalHasher hasher;
    private final KnowledgeTimePolicyV1 knowledgeTime;
    private final Clock clock;

    public MarketDataIngestionService(
            MarketDataIngestionRepository repository,
            MarketDataDatasetVersionRepository datasets,
            IngestionCanonicalHasher hasher,
            KnowledgeTimePolicyV1 knowledgeTime,
            Clock clock
    ) {
        this.repository = repository;
        this.datasets = datasets;
        this.hasher = hasher;
        this.knowledgeTime = knowledgeTime;
        this.clock = clock;
    }

    @Transactional
    public IngestionRun startRun(StartRunCommand command) {
        IngestionValidation.required(command, "command");
        if (command.runNamespace() == IngestionModels.RunNamespace.FORMAL) {
            throw conflict("FORMAL ingestion requires a separately approved source adapter");
        }
        DatasetVersion dataset = requireDataset(command.datasetVersionId());
        verifyDatasetType(command.datasetType(), dataset);
        verifyRequestedRange(command, dataset);
        String datasetLogicalKey = hasher.datasetLogicalKey(dataset);
        IngestionRun retryParent = null;
        String rootRequestLogicalKey;
        int runAttemptNumber;
        if (command.operationType() == IngestionModels.OperationType.RETRY) {
            retryParent = repository.findRunByLogicalKey(command.retryOfRunLogicalKey())
                    .orElseThrow(() -> conflict("RETRY parent ingestion run does not exist"));
            verifyRetryParent(command, datasetLogicalKey, retryParent);
            rootRequestLogicalKey = retryParent.rootRequestLogicalKey();
            runAttemptNumber = Math.addExact(retryParent.runAttemptNumber(), 1);
        } else {
            rootRequestLogicalKey = hasher.rootRequestLogicalKey(
                    datasetLogicalKey, command.datasetType(), command.runNamespace(),
                    command.operationType(), command.requestKey(), command.requestedRangeStart(),
                    command.requestedRangeEnd());
            runAttemptNumber = 1;
        }
        String runLogicalKey = hasher.runLogicalKey(
                datasetLogicalKey, command.datasetType(), command.runNamespace(),
                command.operationType(), command.requestKey(), rootRequestLogicalKey,
                runAttemptNumber, command.requestedRangeStart(), command.requestedRangeEnd(),
                command.retryOfRunLogicalKey());
        Instant now = IngestionValidation.instant(clock.instant(), "now");
        IngestionRun value = repository.insertRun(
                runLogicalKey, dataset.id(), datasetLogicalKey, command.datasetType(),
                command.runNamespace(), command.operationType(), command.requestKey(),
                command.retryOfRunLogicalKey(), rootRequestLogicalKey, runAttemptNumber,
                command.requestedRangeStart(), command.requestedRangeEnd(), now)
                .orElseGet(() -> repository.findRunByRootAttempt(
                        rootRequestLogicalKey, runAttemptNumber).orElseThrow(
                                () -> conflict("ingestion run conflict has no persisted winner")));
        verifyRun(command, datasetLogicalKey, runLogicalKey, rootRequestLogicalKey,
                runAttemptNumber, value);
        return value;
    }

    @Transactional
    public RawRecord appendSecurityStatusRaw(AppendSecurityStatusRawCommand command) {
        return appendRaw(DatasetType.SECURITY_STATUS, command);
    }

    @Transactional
    public RawRecord appendTradingCalendarRaw(AppendTradingCalendarRawCommand command) {
        return appendRaw(DatasetType.TRADING_CALENDAR, command);
    }

    @Transactional
    public ProcessingAttempt recordSecurityStatusAttempt(RecordAttemptCommand command) {
        return recordAttempt(DatasetType.SECURITY_STATUS, command);
    }

    @Transactional
    public ProcessingAttempt recordTradingCalendarAttempt(RecordAttemptCommand command) {
        return recordAttempt(DatasetType.TRADING_CALENDAR, command);
    }

    @Transactional
    public IngestionRun sealRun(SealRunCommand command) {
        IngestionValidation.required(command, "command");
        IngestionRun run = repository.lockRun(command.ingestionRunId())
                .orElseThrow(() -> conflict("ingestion run does not exist"));
        DatasetVersion dataset = requireDataset(run.datasetVersionId());
        List<ManifestEntry> entries = repository.findManifestEntries(run.datasetType(), run.id());
        List<ProcessingAttempt> finalAttempts = repository.findFinalAttempts(
                run.datasetType(), run.id());
        int received = repository.countRunRawRecords(run.datasetType(), run.id());
        if (finalAttempts.size() != received) {
            throw conflict("every received raw record requires a final terminal processing attempt");
        }
        int accepted = Math.toIntExact(finalAttempts.stream()
                .filter(attempt -> attempt.status() == AttemptStatus.COMPLETED).count());
        int rejected = received - accepted;
        AssuranceLevel assurance = AssuranceLevel.mostConservative(
                finalAttempts.stream().map(ProcessingAttempt::assuranceLevel).toList());
        assurance = assurance.conservativeWith(datasetAssuranceCeiling(dataset));
        validateTerminalCounts(command.status(), command.expectedCount(), received, accepted, rejected);

        String manifestHash = hasher.manifestHash(
                run, dataset, command.status(), command.expectedCount(), received, accepted, rejected,
                assurance, entries);
        if (run.sealed()) {
            verifySealedRun(command, manifestHash, received, accepted, rejected, assurance, run);
            return run;
        }
        IngestionRun sealed = repository.sealRun(
                run.id(), command.status(), manifestHash, command.expectedCount(),
                received, accepted, rejected, assurance).orElseThrow(
                        () -> conflict("ingestion run was not sealed"));
        verifySealedRun(command, manifestHash, received, accepted, rejected, assurance, sealed);
        return sealed;
    }

    private RawRecord appendRaw(DatasetType type, AppendRawRecordCommand command) {
        IngestionValidation.required(command, "command");
        IngestionRun run = requireOpenRun(command.ingestionRunId(), type);
        DatasetVersion dataset = requireDataset(run.datasetVersionId());
        if (type == DatasetType.TRADING_CALENDAR) {
            IngestionValidation.dateWithin(
                    command.tradeDate(), dataset.rangeStart(), dataset.rangeEnd(), "tradeDate");
        }
        if (!dataset.source().equals(command.source())
                || !dataset.sourceVersion().equals(command.sourceVersion())) {
            throw conflict("raw record source does not match its dataset version");
        }
        String canonicalPayloadHash = hasher.jsonHash(command.rawPayload());
        if (!canonicalPayloadHash.equals(command.payloadHash())) {
            throw conflict("raw payload hash does not match canonical payload");
        }
        String logicalKey = hasher.rawRecordLogicalKey(
                type, run.runNamespace(), command.source(), command.sourceVersion(),
                command.sourceRecordId(), command.sourceRevision());
        Instant now = IngestionValidation.instant(clock.instant(), "now");
        RawRecord value = repository.insertRawRecord(
                type, run.id(), dataset.id(), logicalKey, run.runNamespace(), command.source(),
                command.sourceVersion(), command.sourceRecordId(), command.sourceRevision(),
                command.sourceInstrumentId(), command.exchange(), command.tradeDate(),
                command.sourcePublishedAt(), command.sourceEffectiveDate(),
                command.sourceEffectiveAt(), now, command.sourceTrustLevel(),
                command.rawPayload(), command.payloadHash()).orElseGet(
                        () -> repository.findRawRecordBySource(
                                type, run.runNamespace(), command.source(), command.sourceVersion(),
                                command.sourceRecordId(), command.sourceRevision()).orElseThrow(
                                 () -> conflict("raw record conflict has no persisted winner")));
        verifyRaw(command, type, run, dataset, logicalKey, value);
        if (!repository.attachRawRecordToRun(type, run.id(), value.id(), now)) {
            throw conflict("raw record was not attached to its ingestion run");
        }
        return value;
    }

    private ProcessingAttempt recordAttempt(DatasetType type, RecordAttemptCommand command) {
        IngestionValidation.required(command, "command");
        IngestionRun run = requireOpenRun(command.ingestionRunId(), type);
        RawRecord raw = repository.findRawRecord(type, command.rawRecordId())
                .orElseThrow(() -> conflict("raw record does not exist"));
        if (raw.datasetVersionId() != run.datasetVersionId()
                || raw.recordNamespace() != run.runNamespace()) {
            throw conflict("raw record does not belong to the ingestion run dataset and namespace");
        }
        if (!repository.isRawRecordAttachedToRun(type, run.id(), raw.id())) {
            throw conflict("processing attempt raw record was not received by this ingestion run");
        }
        DatasetVersion dataset = requireDataset(run.datasetVersionId());

        // 1A deliberately caps all facts below formal PIT until 1B approves a source adapter.
        AssuranceLevel sourceNeutralRequest = command.requestedAssuranceLevel()
                .conservativeWith(AssuranceLevel.RECONSTRUCTED_VERIFIED);
        var assessment = knowledgeTime.assess(
                raw, dataset.trustLevel(), command.publicationTimeVerification(),
                sourceNeutralRequest);
        String resultHash = hasher.jsonHash(command.resultMetadata());
        String attemptLogicalKey = hasher.attemptLogicalKey(
                run.logicalKey(), raw.logicalKey(), command.attemptNo(), command.processorVersion(),
                command.contractVersion());
        ProcessingAttempt value = repository.insertAttempt(
                type, run.id(), raw.id(), command.attemptNo(), attemptLogicalKey, command.status(),
                command.processorVersion(), command.contractVersion(),
                command.publicationTimeVerification(), command.requestedAssuranceLevel(),
                assessment.derivedKnownFrom(),
                assessment.assuranceLevel(), command.errorCode(), command.resultMetadata(),
                resultHash).orElseGet(
                        () -> repository.findAttempt(
                                type, run.id(), raw.id(), command.attemptNo()).orElseThrow(
                                () -> conflict("processing attempt conflict has no persisted winner")));
        verifyAttempt(command, type, run, raw, attemptLogicalKey, assessment, resultHash, value);
        return value;
    }

    private IngestionRun requireOpenRun(long runId, DatasetType type) {
        IngestionRun run = repository.lockRun(runId)
                .orElseThrow(() -> conflict("ingestion run does not exist"));
        if (run.datasetType() != type) throw conflict("ingestion run dataset type mismatch");
        if (run.status() != RunStatus.RUNNING || run.sealed()) {
            throw conflict("ingestion run is sealed");
        }
        return run;
    }

    private DatasetVersion requireDataset(long id) {
        return datasets.findById(id).orElseThrow(() -> conflict("dataset version does not exist"));
    }

    private static void verifyDatasetType(DatasetType type, DatasetVersion dataset) {
        if (!type.name().equals(dataset.datasetType())) {
            throw conflict("ingestion dataset type does not match its dataset version");
        }
    }

    private static AssuranceLevel datasetAssuranceCeiling(DatasetVersion dataset) {
        return switch (dataset.trustLevel()) {
            case OBSERVED, BACKFILLED_VERIFIED -> AssuranceLevel.RECONSTRUCTED_VERIFIED;
            case BACKFILLED_INFERRED -> AssuranceLevel.INFERRED_RESEARCH;
        };
    }

    private static void verifyRequestedRange(StartRunCommand command, DatasetVersion dataset) {
        if (command.requestedRangeStart().isBefore(dataset.rangeStart())
                || command.requestedRangeEnd().isAfter(dataset.rangeEnd())) {
            throw conflict("ingestion requested range must be within the dataset range");
        }
    }

    private static void verifyRetryParent(
            StartRunCommand command,
            String datasetLogicalKey,
            IngestionRun parent
    ) {
        if (!parent.sealed() || !parent.status().terminal()) {
            throw conflict("RETRY parent ingestion run must be sealed");
        }
        if (parent.datasetVersionId() != command.datasetVersionId()
                || parent.datasetType() != command.datasetType()
                || parent.runNamespace() != command.runNamespace()
                || !parent.datasetLogicalKey().equals(datasetLogicalKey)
                || !parent.requestKey().equals(command.requestKey())
                || !parent.requestedRangeStart().equals(command.requestedRangeStart())
                || !parent.requestedRangeEnd().equals(command.requestedRangeEnd())) {
            throw conflict("RETRY parent does not match dataset, namespace, request, or range");
        }
    }

    private static void validateTerminalCounts(
            RunStatus status,
            int expected,
            int received,
            int accepted,
            int rejected
    ) {
        if (received > expected) throw conflict("received count exceeds expected count");
        if (status == RunStatus.COMPLETED
                && (expected == 0 || received != expected || accepted != received || rejected != 0)) {
            throw conflict("COMPLETED run requires all expected records to be accepted");
        }
        if (status == RunStatus.PARTIAL && received == expected && rejected == 0) {
            throw conflict("PARTIAL run requires a missing or rejected record");
        }
    }

    private static void verifyRun(
            StartRunCommand command,
            String datasetLogicalKey,
            String logicalKey,
            String rootRequestLogicalKey,
            int runAttemptNumber,
            IngestionRun value
    ) {
        if (value.datasetVersionId() != command.datasetVersionId()
                || value.datasetType() != command.datasetType()
                || value.runNamespace() != command.runNamespace()
                || value.operationType() != command.operationType()
                || !value.requestKey().equals(command.requestKey())
                || !Objects.equals(value.retryOfRunLogicalKey(), command.retryOfRunLogicalKey())
                || !value.rootRequestLogicalKey().equals(rootRequestLogicalKey)
                || value.runAttemptNumber() != runAttemptNumber
                || !value.requestedRangeStart().equals(command.requestedRangeStart())
                || !value.requestedRangeEnd().equals(command.requestedRangeEnd())
                || !value.datasetLogicalKey().equals(datasetLogicalKey)
                || !value.logicalKey().equals(logicalKey)) {
            throw conflict("ingestion run idempotency key resolved to different content");
        }
    }

    private void verifyRaw(
            AppendRawRecordCommand command,
            DatasetType type,
            IngestionRun run,
            DatasetVersion dataset,
            String logicalKey,
            RawRecord value
    ) {
        if (value.datasetType() != type
                || value.datasetVersionId() != dataset.id()
                || value.recordNamespace() != run.runNamespace()
                || !value.logicalKey().equals(logicalKey)
                || !value.source().equals(command.source())
                || !value.sourceVersion().equals(command.sourceVersion())
                || !value.sourceRecordId().equals(command.sourceRecordId())
                || !value.sourceRevision().equals(command.sourceRevision())
                || !Objects.equals(value.sourceInstrumentId(), command.sourceInstrumentId())
                || !Objects.equals(value.exchange(), command.exchange())
                || !Objects.equals(value.tradeDate(), command.tradeDate())
                || !Objects.equals(value.sourcePublishedAt(), command.sourcePublishedAt())
                || !Objects.equals(value.sourceEffectiveDate(), command.sourceEffectiveDate())
                || !Objects.equals(value.sourceEffectiveAt(), command.sourceEffectiveAt())
                || value.sourceTrustLevel() != command.sourceTrustLevel()
                || !value.payloadHash().equals(command.payloadHash())
                || !hasher.jsonHash(value.rawPayload()).equals(command.payloadHash())) {
            throw conflict("raw record idempotency key resolved to different immutable content");
        }
    }

    private void verifyAttempt(
            RecordAttemptCommand command,
            DatasetType type,
            IngestionRun run,
            RawRecord raw,
            String logicalKey,
            IngestionModels.KnowledgeAssessment assessment,
            String resultHash,
            ProcessingAttempt value
    ) {
        if (value.datasetType() != type
                || value.ingestionRunId() != run.id()
                || value.rawRecordId() != raw.id()
                || value.attemptNo() != command.attemptNo()
                || !value.logicalKey().equals(logicalKey)
                || value.status() != command.status()
                || !value.processorVersion().equals(command.processorVersion())
                || !value.contractVersion().equals(command.contractVersion())
                || value.publicationTimeVerification() != command.publicationTimeVerification()
                || value.requestedAssuranceLevel() != command.requestedAssuranceLevel()
                || !value.derivedKnownFrom().equals(assessment.derivedKnownFrom())
                || !value.knowledgeTimePolicyVersion().equals(KnowledgeTimePolicyV1.VERSION)
                || value.assuranceLevel() != assessment.assuranceLevel()
                || !Objects.equals(value.errorCode(), command.errorCode())
                || !value.resultHash().equals(resultHash)
                || !hasher.jsonHash(value.resultMetadata()).equals(resultHash)) {
            throw conflict("processing attempt idempotency key resolved to different content");
        }
    }

    private static void verifySealedRun(
            SealRunCommand command,
            String manifestHash,
            int received,
            int accepted,
            int rejected,
            AssuranceLevel assurance,
            IngestionRun value
    ) {
        if (!value.sealed()
                || value.status() != command.status()
                || !Objects.equals(value.finalExpectedCount(), command.expectedCount())
                || !Objects.equals(value.finalReceivedCount(), received)
                || !Objects.equals(value.finalAcceptedCount(), accepted)
                || !Objects.equals(value.finalRejectedCount(), rejected)
                || value.assuranceLevel() != assurance
                || !value.manifestHash().equals(manifestHash)) {
            throw conflict("sealed ingestion run does not match the computed manifest");
        }
    }

    private static IngestionDataConflictException conflict(String message) {
        return new IngestionDataConflictException(message);
    }
}

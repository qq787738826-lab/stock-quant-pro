package com.stockquant.server.agent.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.ingestion.IngestionModels.AssuranceLevel;
import com.stockquant.server.agent.ingestion.IngestionModels.AttemptStatus;
import com.stockquant.server.agent.ingestion.IngestionModels.DatasetType;
import com.stockquant.server.agent.ingestion.IngestionModels.IngestionRun;
import com.stockquant.server.agent.ingestion.IngestionModels.KnowledgeAssessment;
import com.stockquant.server.agent.ingestion.IngestionModels.ManifestContractVersion;
import com.stockquant.server.agent.ingestion.IngestionModels.ProcessingAttempt;
import com.stockquant.server.agent.ingestion.IngestionModels.RawRecord;
import com.stockquant.server.agent.ingestion.IngestionModels.RunStatus;
import com.stockquant.server.agent.ingestion.IngestionModels.SealRunCommand;
import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels.MaterializationResult;
import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels.MaterializeSecurityStatusCommand;
import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels.MaterializedSecurityEvent;
import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels.NormalizationOutcome;
import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels.ParsedSecurityStatusRaw;
import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels.SecurityEventManifestEntry;
import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels.SecurityStatusEventLineage;
import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels.SecurityStatusNormalizationResult;
import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels.SecurityIdentity;
import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels.SourceSecurityIdentityMapping;
import com.stockquant.server.agent.temporal.MarketDataDatasetVersionRepository;
import com.stockquant.server.agent.temporal.SecurityStatusEventPayloadContract;
import com.stockquant.server.agent.temporal.SecurityStatusEventType;
import com.stockquant.server.agent.temporal.TemporalModels.DatasetVersion;
import com.stockquant.server.agent.temporal.TemporalTrustLevel;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Atomic TEST/DEMO raw-to-event materialization and Manifest V2 sealing service. */
@Service
public class SecurityStatusEventMaterializationService {

    private static final String INTERNAL_FAILURE = "EVENT_MATERIALIZATION_INTERNAL_ERROR";

    private final MarketDataIngestionRepository ingestion;
    private final SecurityIdentityRepository identities;
    private final SecurityStatusEventMaterializationRepository events;
    private final MarketDataDatasetVersionRepository datasets;
    private final IngestionCanonicalHasher ingestionHasher;
    private final SecurityEventCanonicalHasher eventHasher;
    private final KnowledgeTimePolicyV1 knowledgeTime;
    private final SecurityStatusRawTestV1Parser parser;
    private final SecurityStatusEventClassifier classifier;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final TransactionTemplate requiresNew;

    public SecurityStatusEventMaterializationService(
            MarketDataIngestionRepository ingestion,
            SecurityIdentityRepository identities,
            SecurityStatusEventMaterializationRepository events,
            MarketDataDatasetVersionRepository datasets,
            IngestionCanonicalHasher ingestionHasher,
            SecurityEventCanonicalHasher eventHasher,
            KnowledgeTimePolicyV1 knowledgeTime,
            SecurityStatusRawTestV1Parser parser,
            SecurityStatusEventClassifier classifier,
            ObjectMapper objectMapper,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.ingestion = ingestion;
        this.identities = identities;
        this.events = events;
        this.datasets = datasets;
        this.ingestionHasher = ingestionHasher;
        this.eventHasher = eventHasher;
        this.knowledgeTime = knowledgeTime;
        this.parser = parser;
        this.classifier = classifier;
        this.objectMapper = objectMapper;
        this.clock = clock;
        transaction = new TransactionTemplate(transactionManager);
        requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public MaterializationResult materialize(MaterializeSecurityStatusCommand command) {
        IngestionValidation.required(command, "command");
        try {
            MaterializationResult value = transaction.execute(status -> materializeAtomically(command));
            if (value == null) throw new IllegalStateException("materialization returned no result");
            return value;
        } catch (RuntimeException failure) {
            MaterializationResult winner = null;
            try {
                winner = requiresNew.execute(status -> {
                    Context context = context(command, false);
                    ProcessingAttempt attempt = ingestion.findAttempt(
                            DatasetType.SECURITY_STATUS, context.run().id(), context.raw().id(),
                            command.attemptNo()).orElse(null);
                    return attempt == null ? null : existingResult(command, context, attempt);
                });
            } catch (RuntimeException reconciliationFailure) {
                failure.addSuppressed(reconciliationFailure);
            }
            if (winner != null) return winner;
            try {
                requiresNew.executeWithoutResult(status -> recordProjectionFailure(command));
            } catch (RuntimeException auditFailure) {
                failure.addSuppressed(auditFailure);
            }
            throw failure;
        }
    }

    public IngestionRun sealRun(SealRunCommand command) {
        IngestionValidation.required(command, "command");
        IngestionRun value = transaction.execute(status -> sealAtomically(command));
        if (value == null) throw new IllegalStateException("V2 sealing returned no result");
        return value;
    }

    private MaterializationResult materializeAtomically(MaterializeSecurityStatusCommand command) {
        Context context = context(command);
        var existingAttempt = ingestion.findAttempt(
                DatasetType.SECURITY_STATUS, context.run().id(), context.raw().id(),
                command.attemptNo());
        if (existingAttempt.isPresent()) {
            return existingResult(command, context, existingAttempt.get());
        }

        if (!SecurityEventMaterializationModels.RAW_CONTRACT_VERSION.equals(
                command.rawContractVersion())) {
            return persistNoEvent(command, context, AttemptStatus.UNSUPPORTED_CONTRACT,
                    NormalizationOutcome.UNSUPPORTED_CONTRACT,
                    "UNSUPPORTED_RAW_CONTRACT", null, null, context.assessment().assuranceLevel());
        }
        if (!SecurityEventMaterializationModels.NORMALIZER_VERSION.equals(
                command.normalizerVersion())
                || !SecurityEventMaterializationModels.TRANSITION_RULE_VERSION.equals(
                        command.transitionRuleVersion())) {
            return persistNoEvent(command, context, AttemptStatus.UNSUPPORTED_CONTRACT,
                    NormalizationOutcome.UNSUPPORTED_CONTRACT,
                    "UNSUPPORTED_MATERIALIZATION_CONTRACT", null, null,
                    context.assessment().assuranceLevel());
        }
        if (context.raw().sourceInstrumentId() == null
                || context.raw().sourceEffectiveDate() == null) {
            return persistNoEvent(command, context, AttemptStatus.REJECTED,
                    NormalizationOutcome.REJECTED, "RAW_REQUIRED_IDENTITY_OR_EFFECTIVE_DATE_MISSING",
                    null, null, context.assessment().assuranceLevel());
        }

        ParsedSecurityStatusRaw parsed;
        try {
            parsed = parser.parse(context.raw().rawPayload());
        } catch (SecurityStatusRawTestV1Parser.UnsupportedRawContractException error) {
            return persistNoEvent(command, context, AttemptStatus.UNSUPPORTED_CONTRACT,
                    NormalizationOutcome.UNSUPPORTED_CONTRACT, "UNSUPPORTED_RAW_CONTRACT",
                    null, null, context.assessment().assuranceLevel());
        } catch (IllegalArgumentException error) {
            return persistNoEvent(command, context, AttemptStatus.REJECTED,
                    NormalizationOutcome.REJECTED, "RAW_CONTRACT_INVALID",
                    null, null, context.assessment().assuranceLevel());
        }

        SourceSecurityIdentityMapping mapping = identities.findMapping(
                context.run().runNamespace(), context.raw().source(),
                context.raw().sourceVersion(), context.raw().sourceInstrumentId()).orElse(null);
        if (mapping == null) {
            return persistNoEvent(command, context, AttemptStatus.IDENTITY_UNRESOLVED,
                    NormalizationOutcome.IDENTITY_UNRESOLVED, "IDENTITY_UNRESOLVED",
                    null, null, context.assessment().assuranceLevel());
        }
        SecurityIdentity identity = identities.findIdentity(mapping.securityLogicalKey())
                .orElseThrow(() -> conflict("identity mapping references a missing identity"));
        verifyMapping(context, mapping, identity);

        events.lockSecurityIdentity(identity.securityLogicalKey());
        if (events.unresolvedLegacyEventExistsByRawRevision(
                context.run().runNamespace(), context.raw().source(),
                context.raw().sourceVersion(), context.raw().sourceRecordId(),
                context.raw().sourceRevision())) {
            return persistNoEvent(command, context, AttemptStatus.CONFLICT,
                    NormalizationOutcome.CONFLICT, "LEGACY_EVENT_IDENTITY_UNRESOLVED",
                    null, null, context.assessment().assuranceLevel());
        }
        MaterializedSecurityEvent existing = events.findEventByRawRevision(
                context.run().runNamespace(), context.raw().source(),
                context.raw().sourceVersion(), context.raw().sourceRecordId(),
                context.raw().sourceRevision()).orElse(null);
        if (existing != null) {
            return reuseEvent(command, context, parsed, identity, mapping, existing);
        }

        MaterializedSecurityEvent predecessor = events.findChainHead(
                context.run().runNamespace(), identity.securityLogicalKey(),
                SecurityStatusEventPayloadContract.VERSION).orElse(null);
        AssuranceLevel effectiveAssurance = effectiveAssurance(
                context.assessment().assuranceLevel(), identity, mapping, predecessor);
        if (predecessor != null
                && !context.raw().sourceEffectiveDate().isAfter(predecessor.effectiveFrom())) {
            return persistNoEvent(command, context, AttemptStatus.UNSUPPORTED_CONTRACT,
                    NormalizationOutcome.UNSUPPORTED_CONTRACT,
                    "V1_EFFECTIVE_DATE_MUST_FOLLOW_PREDECESSOR", identity.securityLogicalKey(),
                    predecessor.eventLogicalKey(), effectiveAssurance);
        }

        SecurityStatusEventClassifier.Classification classification =
                classifier.classify(parsed, predecessor);
        if (!classification.noStateChange() && !classification.unsupported()
                && predecessor != null
                && events.sameDateNoStateChangeExists(
                        context.run().runNamespace(), identity.securityLogicalKey(),
                        predecessor.eventLogicalKey(), context.raw().sourceEffectiveDate())) {
            return persistNoEvent(command, context, AttemptStatus.UNSUPPORTED_CONTRACT,
                    NormalizationOutcome.UNSUPPORTED_CONTRACT,
                    "V1_SAME_DATE_NO_STATE_CHANGE_CONFLICT", identity.securityLogicalKey(),
                    predecessor.eventLogicalKey(), effectiveAssurance);
        }
        if (classification.noStateChange()) {
            return persistNoEvent(command, context, AttemptStatus.COMPLETED,
                    NormalizationOutcome.NO_STATE_CHANGE, null, identity.securityLogicalKey(),
                    predecessor == null ? null : predecessor.eventLogicalKey(), effectiveAssurance);
        }
        if (classification.unsupported()) {
            return persistNoEvent(command, context, AttemptStatus.UNSUPPORTED_CONTRACT,
                    NormalizationOutcome.UNSUPPORTED_CONTRACT, classification.errorCode(),
                    identity.securityLogicalKey(),
                    predecessor == null ? null : predecessor.eventLogicalKey(), effectiveAssurance);
        }
        return materializeEvent(
                command, context, parsed, identity, mapping, predecessor,
                classification.eventType(), effectiveAssurance);
    }

    private MaterializationResult materializeEvent(
            MaterializeSecurityStatusCommand command,
            Context context,
            ParsedSecurityStatusRaw parsed,
            SecurityIdentity identity,
            SourceSecurityIdentityMapping mapping,
            MaterializedSecurityEvent predecessor,
            SecurityStatusEventType eventType,
            AssuranceLevel assurance
    ) {
        ProcessingAttempt attempt = insertAttempt(
                command, context, AttemptStatus.COMPLETED, null);
        var payload = SecurityStatusEventPayloadContract.payload(parsed.state());
        String payloadHash = SecurityStatusEventPayloadContract.hash(payload);
        String eventLogicalKey = eventHasher.eventLogicalKey(
                context.raw().logicalKey(), SecurityStatusEventPayloadContract.VERSION, eventType);
        TemporalTrustLevel eventTrust = effectiveTrust(context, predecessor);
        Instant recordedAt = IngestionValidation.instant(clock.instant(), "now");
        if (recordedAt.isBefore(attempt.derivedKnownFrom())) recordedAt = attempt.derivedKnownFrom();
        MaterializedSecurityEvent event;
        try {
            event = events.insertEvent(
                    context.dataset().id(), parsed.symbol(), eventType,
                    context.raw().sourceEffectiveDate(), context.raw().sourcePublishedAt(),
                    attempt.derivedKnownFrom(), recordedAt, context.raw().source(),
                    context.raw().sourceVersion(), context.raw().sourceRecordId(),
                    context.raw().sourceRevision(), eventTrust, payload, payloadHash,
                    predecessor == null ? null : predecessor.id(), eventLogicalKey,
                    SecurityStatusEventPayloadContract.VERSION, context.run().runNamespace(),
                    assurance, identity.securityLogicalKey()).orElseThrow(
                            () -> conflict("event insert returned no row"));
        } catch (RuntimeException duplicateOrConflict) {
            MaterializedSecurityEvent winner = events.findEventByLogicalKey(eventLogicalKey)
                    .orElse(null);
            if (winner == null) throw duplicateOrConflict;
            verifyEvent(context, parsed, identity, predecessor, eventType, assurance, winner);
            SecurityStatusEventLineage winnerLineage = events.findLineageByEvent(winner.id())
                    .orElseThrow(() -> conflict("reused event has no lineage"));
            verifyPersistedLineage(command, context, identity, mapping, predecessor, winner,
                    assurance, winnerLineage);
            return persistEventResult(
                    command, context, attempt, NormalizationOutcome.EVENT_REUSED,
                    winner, winnerLineage, assurance);
        }
        verifyEvent(context, parsed, identity, predecessor, eventType, assurance, event);
        String lineageHash = eventHasher.lineageHash(
                event.eventLogicalKey(), context.run().datasetLogicalKey(),
                context.raw().logicalKey(), context.run().logicalKey(), attempt.logicalKey(),
                mapping.mappingLogicalKey(), identity.securityLogicalKey(),
                predecessor == null ? null : predecessor.eventLogicalKey(),
                context.run().runNamespace(), SecurityStatusEventPayloadContract.VERSION,
                command.normalizerVersion(), command.transitionRuleVersion(), payloadHash,
                assurance);
        SecurityStatusEventLineage lineage = events.insertLineage(
                event, context.dataset().id(), context.run().datasetLogicalKey(),
                context.raw().id(), context.raw().logicalKey(), context.run().id(),
                context.run().logicalKey(), attempt.id(), attempt.logicalKey(), identity, mapping,
                predecessor, command.normalizerVersion(), command.transitionRuleVersion(),
                assurance, lineageHash).orElseGet(
                        () -> events.findLineageByEvent(event.id()).orElseThrow(
                                () -> conflict("event lineage conflict has no winner")));
        verifyLineage(command, context, attempt, identity, mapping, predecessor, event,
                assurance, lineageHash, lineage);
        return persistEventResult(command, context, attempt,
                NormalizationOutcome.EVENT_MATERIALIZED, event, lineage, assurance);
    }

    private MaterializationResult reuseEvent(
            MaterializeSecurityStatusCommand command,
            Context context,
            ParsedSecurityStatusRaw parsed,
            SecurityIdentity identity,
            SourceSecurityIdentityMapping mapping,
            MaterializedSecurityEvent event
    ) {
        SecurityStatusEventLineage lineage = events.findLineageByEvent(event.id())
                .orElseThrow(() -> conflict("reused event has no authoritative lineage"));
        MaterializedSecurityEvent predecessor = event.predecessorEventId() == null ? null
                : events.findEventByLogicalKey(lineage.predecessorEventLogicalKey())
                        .orElseThrow(() -> conflict("reused event predecessor is missing"));
        AssuranceLevel assurance = effectiveAssurance(
                context.assessment().assuranceLevel(), identity, mapping, predecessor);
        try {
            verifyEvent(context, parsed, identity, predecessor,
                    event.eventType(), assurance, event);
        } catch (IngestionDataConflictException conflict) {
            return persistNoEvent(command, context, AttemptStatus.CONFLICT,
                    NormalizationOutcome.CONFLICT, "EVENT_REUSE_CONTENT_CONFLICT",
                    identity.securityLogicalKey(),
                    predecessor == null ? null : predecessor.eventLogicalKey(), assurance);
        }
        if (!lineage.normalizerVersion().equals(command.normalizerVersion())
                || !lineage.transitionRuleVersion().equals(command.transitionRuleVersion())
                || !lineage.mappingLogicalKey().equals(mapping.mappingLogicalKey())) {
            return persistNoEvent(command, context, AttemptStatus.CONFLICT,
                    NormalizationOutcome.CONFLICT, "EVENT_REUSE_LINEAGE_CONFLICT",
                    identity.securityLogicalKey(),
                    predecessor == null ? null : predecessor.eventLogicalKey(), assurance);
        }
        verifyPersistedLineage(command, context, identity, mapping, predecessor, event,
                assurance, lineage);
        ProcessingAttempt attempt = insertAttempt(command, context, AttemptStatus.COMPLETED, null);
        return persistEventResult(command, context, attempt,
                NormalizationOutcome.EVENT_REUSED, event, lineage, assurance);
    }

    private MaterializationResult persistEventResult(
            MaterializeSecurityStatusCommand command,
            Context context,
            ProcessingAttempt attempt,
            NormalizationOutcome outcome,
            MaterializedSecurityEvent event,
            SecurityStatusEventLineage lineage,
            AssuranceLevel assurance
    ) {
        String resultHash = eventHasher.normalizationResultHash(
                attempt.logicalKey(), outcome, event.eventLogicalKey(),
                event.securityLogicalKey(), lineage.predecessorEventLogicalKey(),
                command.normalizerVersion(), command.transitionRuleVersion(), assurance, null);
        SecurityStatusNormalizationResult result = events.insertNormalizationResult(
                attempt.id(), attempt.logicalKey(), outcome, event,
                event.securityLogicalKey(), lineage.predecessorEventLogicalKey(),
                command.normalizerVersion(), command.transitionRuleVersion(), assurance,
                resultHash, null).orElseGet(
                        () -> events.findNormalizationResult(attempt.id()).orElseThrow(
                                () -> conflict("normalization result conflict has no winner")));
        verifyResult(command, attempt, outcome, event, event.securityLogicalKey(),
                lineage.predecessorEventLogicalKey(), assurance, resultHash, null, result);
        return new MaterializationResult(attempt, result, event, lineage);
    }

    private MaterializationResult persistNoEvent(
            MaterializeSecurityStatusCommand command,
            Context context,
            AttemptStatus status,
            NormalizationOutcome outcome,
            String errorCode,
            String securityLogicalKey,
            String predecessorEventLogicalKey,
            AssuranceLevel assurance
    ) {
        ProcessingAttempt attempt = insertAttempt(command, context, status, errorCode);
        String resultHash = eventHasher.normalizationResultHash(
                attempt.logicalKey(), outcome, null, securityLogicalKey,
                predecessorEventLogicalKey, command.normalizerVersion(),
                command.transitionRuleVersion(), assurance, errorCode);
        SecurityStatusNormalizationResult result = events.insertNormalizationResult(
                attempt.id(), attempt.logicalKey(), outcome, null, securityLogicalKey,
                predecessorEventLogicalKey, command.normalizerVersion(),
                command.transitionRuleVersion(), assurance, resultHash, errorCode).orElseGet(
                        () -> events.findNormalizationResult(attempt.id()).orElseThrow(
                                () -> conflict("normalization result conflict has no winner")));
        verifyResult(command, attempt, outcome, null, securityLogicalKey,
                predecessorEventLogicalKey, assurance, resultHash, errorCode, result);
        return new MaterializationResult(attempt, result, null, null);
    }

    private ProcessingAttempt insertAttempt(
            MaterializeSecurityStatusCommand command,
            Context context,
            AttemptStatus status,
            String errorCode
    ) {
        String logicalKey = ingestionHasher.attemptLogicalKey(
                context.run().logicalKey(), context.raw().logicalKey(), command.attemptNo(),
                command.processorVersion(), command.rawContractVersion());
        String emptyHash = ingestionHasher.jsonHash(objectMapper.createObjectNode());
        ProcessingAttempt value = ingestion.insertAttempt(
                DatasetType.SECURITY_STATUS, context.run().id(), context.raw().id(),
                command.attemptNo(), logicalKey, status, command.processorVersion(),
                command.rawContractVersion(), command.publicationTimeVerification(),
                command.requestedAssuranceLevel(), context.assessment().derivedKnownFrom(),
                context.assessment().assuranceLevel(), errorCode,
                objectMapper.createObjectNode(), emptyHash).orElseGet(
                        () -> ingestion.findAttempt(DatasetType.SECURITY_STATUS,
                                context.run().id(), context.raw().id(), command.attemptNo())
                                .orElseThrow(() -> conflict(
                                        "processing attempt conflict has no winner")));
        if (!value.logicalKey().equals(logicalKey) || value.status() != status
                || !value.processorVersion().equals(command.processorVersion())
                || !value.contractVersion().equals(command.rawContractVersion())
                || value.publicationTimeVerification() != command.publicationTimeVerification()
                || value.requestedAssuranceLevel() != command.requestedAssuranceLevel()
                || !value.derivedKnownFrom().equals(context.assessment().derivedKnownFrom())
                || value.assuranceLevel() != context.assessment().assuranceLevel()
                || !Objects.equals(value.errorCode(), errorCode)
                || !value.resultHash().equals(emptyHash)) {
            throw conflict("processing attempt idempotency resolved to different content");
        }
        return value;
    }

    private Context context(MaterializeSecurityStatusCommand command) {
        return context(command, true);
    }

    private Context context(MaterializeSecurityStatusCommand command, boolean requireOpen) {
        IngestionRun run = ingestion.lockRun(command.ingestionRunId())
                .orElseThrow(() -> conflict("ingestion run does not exist"));
        if (run.datasetType() != DatasetType.SECURITY_STATUS
                || run.manifestContractVersion()
                != ManifestContractVersion.INGESTION_MANIFEST_V2_SECURITY_EVENT) {
            throw conflict("materialization requires a Manifest V2 SECURITY_STATUS run");
        }
        if (run.runNamespace() == IngestionModels.RunNamespace.FORMAL) {
            throw conflict("FORMAL event materialization is not approved");
        }
        if (requireOpen && (run.status() != RunStatus.RUNNING || run.sealed())) {
            throw conflict("materialization run is sealed");
        }
        RawRecord raw = ingestion.findRawRecord(DatasetType.SECURITY_STATUS, command.rawRecordId())
                .orElseThrow(() -> conflict("security raw record does not exist"));
        if (raw.datasetVersionId() != run.datasetVersionId()
                || raw.recordNamespace() != run.runNamespace()
                || !ingestion.isRawRecordAttachedToRun(
                        DatasetType.SECURITY_STATUS, run.id(), raw.id())) {
            throw conflict("security raw is not part of the materialization run");
        }
        DatasetVersion dataset = datasets.findById(run.datasetVersionId())
                .orElseThrow(() -> conflict("dataset version does not exist"));
        AssuranceLevel request = command.requestedAssuranceLevel()
                .conservativeWith(AssuranceLevel.RECONSTRUCTED_VERIFIED);
        KnowledgeAssessment assessment = knowledgeTime.assess(
                raw, dataset.trustLevel(), command.publicationTimeVerification(), request);
        return new Context(run, raw, dataset, assessment);
    }

    private MaterializationResult existingResult(
            MaterializeSecurityStatusCommand command,
            Context context,
            ProcessingAttempt attempt
    ) {
        SecurityStatusNormalizationResult result = events.findNormalizationResult(attempt.id())
                .orElseThrow(() -> conflict("existing V2 attempt has no normalization result"));
        verifyExistingAttempt(command, context, attempt, result);

        ParsedSecurityStatusRaw parsed = null;
        SourceSecurityIdentityMapping mapping = null;
        SecurityIdentity identity = null;
        MaterializedSecurityEvent predecessor = null;
        AssuranceLevel assurance = result.outcome() == NormalizationOutcome.PROJECTION_FAILED
                && result.securityLogicalKey() == null
                ? AssuranceLevel.INFERRED_RESEARCH
                : context.assessment().assuranceLevel();
        if (result.securityLogicalKey() != null) {
            parsed = parser.parse(context.raw().rawPayload());
            mapping = identities.findMapping(
                    context.run().runNamespace(), context.raw().source(),
                    context.raw().sourceVersion(), context.raw().sourceInstrumentId())
                    .orElseThrow(() -> conflict("existing result identity mapping is missing"));
            identity = identities.findIdentity(mapping.securityLogicalKey())
                    .orElseThrow(() -> conflict("existing result identity is missing"));
            verifyMapping(context, mapping, identity);
            if (!identity.securityLogicalKey().equals(result.securityLogicalKey())) {
                throw conflict("existing result stable identity differs from its raw mapping");
            }
            events.lockSecurityIdentity(identity.securityLogicalKey());
            if (result.predecessorEventLogicalKey() != null) {
                predecessor = events.findEventByLogicalKey(result.predecessorEventLogicalKey())
                        .orElseThrow(() -> conflict("existing result predecessor is missing"));
                verifyPredecessor(context, identity, predecessor);
            }
            assurance = effectiveAssurance(
                    context.assessment().assuranceLevel(), identity, mapping, predecessor);
        }

        MaterializedSecurityEvent event = null;
        SecurityStatusEventLineage lineage = null;
        if (result.outcome().referencesEvent()) {
            if (parsed == null || identity == null || mapping == null) {
                throw conflict("existing event result has no resolved raw identity chain");
            }
            event = events.findEventByLogicalKey(result.eventLogicalKey()).orElseThrow(
                    () -> conflict("existing normalization event is missing"));
            lineage = events.findLineageByEvent(event.id()).orElseThrow(
                    () -> conflict("existing normalization lineage is missing"));
            var classification = classifier.classify(parsed, predecessor);
            if (classification.unsupported() || classification.noStateChange()
                    || classification.eventType() != event.eventType()) {
                throw conflict("existing event no longer matches the frozen V1 classification");
            }
            verifyEvent(context, parsed, identity, predecessor,
                    classification.eventType(), assurance, event);
            verifyPersistedLineage(command, context, identity, mapping, predecessor,
                    event, assurance, lineage);
            if (result.outcome() == NormalizationOutcome.EVENT_MATERIALIZED
                    && lineage.processingAttemptId() != attempt.id()) {
                throw conflict("EVENT_MATERIALIZED does not own its lineage attempt");
            }
            if (result.outcome() == NormalizationOutcome.EVENT_REUSED
                    && lineage.processingAttemptId() == attempt.id()) {
                throw conflict("EVENT_REUSED unexpectedly owns the event lineage");
            }
        } else if (result.outcome() == NormalizationOutcome.NO_STATE_CHANGE) {
            if (parsed == null || predecessor == null
                    || !parsed.symbol().equals(predecessor.symbol())
                    || !SecurityStatusEventPayloadContract.parse(predecessor.payload())
                            .equals(parsed.state())
                    || !context.raw().sourceEffectiveDate().isAfter(
                            predecessor.effectiveFrom())) {
                throw conflict("existing NO_STATE_CHANGE does not match its predecessor state");
            }
        }

        String expectedResultHash = eventHasher.normalizationResultHash(
                attempt.logicalKey(), result.outcome(),
                event == null ? null : event.eventLogicalKey(), result.securityLogicalKey(),
                result.predecessorEventLogicalKey(), command.normalizerVersion(),
                command.transitionRuleVersion(), assurance, result.errorCode());
        verifyResult(command, attempt, result.outcome(), event, result.securityLogicalKey(),
                result.predecessorEventLogicalKey(), assurance, expectedResultHash,
                result.errorCode(), result);
        return new MaterializationResult(attempt, result, event, lineage);
    }

    private void recordProjectionFailure(MaterializeSecurityStatusCommand command) {
        Context context = context(command);
        if (ingestion.findAttempt(DatasetType.SECURITY_STATUS, context.run().id(),
                context.raw().id(), command.attemptNo()).isPresent()) return;
        FailureChain failureChain = resolveFailureChain(command, context);
        persistNoEvent(command, context, AttemptStatus.PROJECTION_FAILED,
                NormalizationOutcome.PROJECTION_FAILED, INTERNAL_FAILURE,
                failureChain.securityLogicalKey(), failureChain.predecessorEventLogicalKey(),
                failureChain.assuranceLevel());
    }

    private FailureChain resolveFailureChain(
            MaterializeSecurityStatusCommand command,
            Context context
    ) {
        try {
            if (!SecurityEventMaterializationModels.RAW_CONTRACT_VERSION.equals(
                    command.rawContractVersion())
                    || !SecurityEventMaterializationModels.NORMALIZER_VERSION.equals(
                            command.normalizerVersion())
                    || !SecurityEventMaterializationModels.TRANSITION_RULE_VERSION.equals(
                            command.transitionRuleVersion())
                    || context.raw().sourceInstrumentId() == null
                    || context.raw().sourceEffectiveDate() == null) {
                return FailureChain.unresolved();
            }
            parser.parse(context.raw().rawPayload());
            SourceSecurityIdentityMapping mapping = identities.findMapping(
                    context.run().runNamespace(), context.raw().source(),
                    context.raw().sourceVersion(), context.raw().sourceInstrumentId()).orElse(null);
            if (mapping == null) return FailureChain.unresolved();
            SecurityIdentity identity = identities.findIdentity(mapping.securityLogicalKey())
                    .orElse(null);
            if (identity == null) return FailureChain.unresolved();
            verifyMapping(context, mapping, identity);
            events.lockSecurityIdentity(identity.securityLogicalKey());
            MaterializedSecurityEvent predecessor = events.findChainHead(
                    context.run().runNamespace(), identity.securityLogicalKey(),
                    SecurityStatusEventPayloadContract.VERSION).orElse(null);
            AssuranceLevel assurance = effectiveAssurance(
                    context.assessment().assuranceLevel(), identity, mapping, predecessor);
            return new FailureChain(identity.securityLogicalKey(),
                    predecessor == null ? null : predecessor.eventLogicalKey(), assurance);
        } catch (RuntimeException unresolved) {
            return FailureChain.unresolved();
        }
    }

    private IngestionRun sealAtomically(SealRunCommand command) {
        IngestionRun run = ingestion.lockRun(command.ingestionRunId())
                .orElseThrow(() -> conflict("ingestion run does not exist"));
        if (run.manifestContractVersion()
                != ManifestContractVersion.INGESTION_MANIFEST_V2_SECURITY_EVENT
                || run.datasetType() != DatasetType.SECURITY_STATUS) {
            throw conflict("security event sealing requires a Manifest V2 security run");
        }
        DatasetVersion dataset = datasets.findById(run.datasetVersionId())
                .orElseThrow(() -> conflict("dataset version does not exist"));
        List<ProcessingAttempt> finalAttempts = ingestion.findFinalAttempts(
                DatasetType.SECURITY_STATUS, run.id());
        List<SecurityStatusNormalizationResult> finalResults = events.findFinalResults(run.id());
        List<SecurityEventManifestEntry> entries = events.findManifestV2Entries(run.id());
        int received = ingestion.countRunRawRecords(DatasetType.SECURITY_STATUS, run.id());
        if (finalAttempts.size() != received || finalResults.size() != received) {
            throw conflict("every received raw requires a final attempt and normalization result");
        }
        int accepted = Math.toIntExact(finalAttempts.stream()
                .filter(value -> value.status() == AttemptStatus.COMPLETED).count());
        int rejected = received - accepted;
        AssuranceLevel assurance = AssuranceLevel.mostConservative(
                finalResults.stream().map(SecurityStatusNormalizationResult::assuranceLevel)
                        .toList());
        validateTerminalCounts(
                command.status(), command.expectedCount(), received, accepted, rejected);
        String manifestHash = eventHasher.manifestV2Hash(
                run, dataset, command.status(), command.expectedCount(), received,
                accepted, rejected, assurance, entries);
        if (run.sealed()) {
            verifySealed(command, manifestHash, received, accepted, rejected, assurance, run);
            return run;
        }
        IngestionRun sealed = ingestion.sealRun(
                run.id(), command.status(), manifestHash, command.expectedCount(), received,
                accepted, rejected, assurance).orElseThrow(
                        () -> conflict("Manifest V2 run was not sealed"));
        verifySealed(command, manifestHash, received, accepted, rejected, assurance, sealed);
        return sealed;
    }

    private static void verifyMapping(
            Context context,
            SourceSecurityIdentityMapping mapping,
            SecurityIdentity identity
    ) {
        if (mapping.recordNamespace() != context.run().runNamespace()
                || identity.recordNamespace() != context.run().runNamespace()
                || !mapping.source().equals(context.raw().source())
                || !mapping.sourceVersion().equals(context.raw().sourceVersion())
                || !mapping.sourceInstrumentId().equals(context.raw().sourceInstrumentId())
                || !mapping.securityLogicalKey().equals(identity.securityLogicalKey())) {
            throw conflict("security identity mapping is outside the raw ingestion chain");
        }
    }

    private void verifyExistingAttempt(
            MaterializeSecurityStatusCommand command,
            Context context,
            ProcessingAttempt attempt,
            SecurityStatusNormalizationResult result
    ) {
        String logicalKey = ingestionHasher.attemptLogicalKey(
                context.run().logicalKey(), context.raw().logicalKey(), command.attemptNo(),
                command.processorVersion(), command.rawContractVersion());
        String emptyHash = ingestionHasher.jsonHash(objectMapper.createObjectNode());
        if (attempt.ingestionRunId() != context.run().id()
                || attempt.rawRecordId() != context.raw().id()
                || attempt.attemptNo() != command.attemptNo()
                || !attempt.logicalKey().equals(logicalKey)
                || attempt.status() != result.outcome().requiredAttemptStatus()
                || !attempt.processorVersion().equals(command.processorVersion())
                || !attempt.contractVersion().equals(command.rawContractVersion())
                || attempt.publicationTimeVerification() != command.publicationTimeVerification()
                || attempt.requestedAssuranceLevel() != command.requestedAssuranceLevel()
                || !attempt.derivedKnownFrom().equals(context.assessment().derivedKnownFrom())
                || !attempt.knowledgeTimePolicyVersion().equals(KnowledgeTimePolicyV1.VERSION)
                || attempt.assuranceLevel() != context.assessment().assuranceLevel()
                || !Objects.equals(attempt.errorCode(), result.errorCode())
                || !attempt.resultMetadata().equals(objectMapper.createObjectNode())
                || !attempt.resultHash().equals(emptyHash)
                || !result.normalizerVersion().equals(command.normalizerVersion())
                || !result.transitionRuleVersion().equals(command.transitionRuleVersion())) {
            throw conflict("materialization idempotency key resolved to different content");
        }
    }

    private static void verifyPredecessor(
            Context context,
            SecurityIdentity identity,
            MaterializedSecurityEvent predecessor
    ) {
        if (predecessor.recordNamespace() != context.run().runNamespace()
                || !predecessor.securityLogicalKey().equals(identity.securityLogicalKey())
                || !predecessor.eventContractVersion().equals(
                        SecurityStatusEventPayloadContract.VERSION)
                || predecessor.eventLogicalKey() == null) {
            throw conflict("existing predecessor is outside the resolved identity chain");
        }
    }

    private static AssuranceLevel effectiveAssurance(
            AssuranceLevel attempt,
            SecurityIdentity identity,
            SourceSecurityIdentityMapping mapping,
            MaterializedSecurityEvent predecessor
    ) {
        AssuranceLevel value = SecurityEventMaterializationModels.conservative(
                attempt, identity.assuranceLevel(), mapping.mappingAssuranceLevel());
        return predecessor == null ? value : value.conservativeWith(predecessor.assuranceLevel());
    }

    private static TemporalTrustLevel effectiveTrust(
            Context context,
            MaterializedSecurityEvent predecessor
    ) {
        return predecessor == null
                ? TemporalTrustLevel.mostConservative(
                        context.dataset().trustLevel(), context.raw().sourceTrustLevel())
                : TemporalTrustLevel.mostConservative(
                        context.dataset().trustLevel(), context.raw().sourceTrustLevel(),
                        predecessor.trustLevel());
    }

    private void verifyEvent(
            Context context,
            ParsedSecurityStatusRaw parsed,
            SecurityIdentity identity,
            MaterializedSecurityEvent predecessor,
            SecurityStatusEventType eventType,
            AssuranceLevel assurance,
            MaterializedSecurityEvent event
    ) {
        String payloadHash = SecurityStatusEventPayloadContract.hash(event.payload());
        String eventLogicalKey = eventHasher.eventLogicalKey(
                context.raw().logicalKey(), SecurityStatusEventPayloadContract.VERSION, eventType);
        if (event.datasetVersionId() != context.dataset().id()
                || !event.symbol().equals(parsed.symbol())
                || event.eventType() != eventType
                || !event.effectiveFrom().equals(context.raw().sourceEffectiveDate())
                || event.effectiveTo() != null
                || !Objects.equals(event.publishedAt(), context.raw().sourcePublishedAt())
                || !event.knownAt().equals(context.assessment().derivedKnownFrom())
                || !event.source().equals(context.raw().source())
                || !event.sourceVersion().equals(context.raw().sourceVersion())
                || !event.sourceRecordId().equals(context.raw().sourceRecordId())
                || !event.sourceRevision().equals(context.raw().sourceRevision())
                || event.trustLevel() != effectiveTrust(context, predecessor)
                || !event.payloadHash().equals(payloadHash)
                || !SecurityStatusEventPayloadContract.parse(event.payload()).equals(parsed.state())
                || !event.eventLogicalKey().equals(eventLogicalKey)
                || !event.securityLogicalKey().equals(identity.securityLogicalKey())
                || event.recordNamespace() != context.run().runNamespace()
                || !event.eventContractVersion().equals(SecurityStatusEventPayloadContract.VERSION)
                || event.assuranceLevel() != assurance
                || !Objects.equals(event.predecessorEventId(),
                        predecessor == null ? null : predecessor.id())) {
            throw conflict("event idempotency resolved to different immutable content");
        }
    }

    private void verifyLineage(
            MaterializeSecurityStatusCommand command,
            Context context,
            ProcessingAttempt attempt,
            SecurityIdentity identity,
            SourceSecurityIdentityMapping mapping,
            MaterializedSecurityEvent predecessor,
            MaterializedSecurityEvent event,
            AssuranceLevel assurance,
            String lineageHash,
            SecurityStatusEventLineage lineage
    ) {
        verifyPersistedLineage(command, context, identity, mapping, predecessor, event,
                assurance, lineage);
        if (lineage.ingestionRunId() != context.run().id()
                || !lineage.ingestionRunLogicalKey().equals(context.run().logicalKey())
                || lineage.processingAttemptId() != attempt.id()
                || !lineage.attemptLogicalKey().equals(attempt.logicalKey())
                || !lineage.lineageHash().equals(lineageHash)) {
            throw conflict("event lineage idempotency resolved to different immutable content");
        }
    }

    private void verifyPersistedLineage(
            MaterializeSecurityStatusCommand command,
            Context context,
            SecurityIdentity identity,
            SourceSecurityIdentityMapping mapping,
            MaterializedSecurityEvent predecessor,
            MaterializedSecurityEvent event,
            AssuranceLevel assurance,
            SecurityStatusEventLineage lineage
    ) {
        IngestionRun lineageRun = ingestion.findRun(lineage.ingestionRunId())
                .orElseThrow(() -> conflict("lineage origin run is missing"));
        RawRecord lineageRaw = ingestion.findRawRecord(
                DatasetType.SECURITY_STATUS, lineage.rawRecordId())
                .orElseThrow(() -> conflict("lineage origin raw is missing"));
        ProcessingAttempt lineageAttempt = ingestion.findAttemptById(
                DatasetType.SECURITY_STATUS, lineage.processingAttemptId())
                .orElseThrow(() -> conflict("lineage origin attempt is missing"));
        String expectedHash = eventHasher.lineageHash(
                event.eventLogicalKey(), context.run().datasetLogicalKey(),
                context.raw().logicalKey(), lineage.ingestionRunLogicalKey(),
                lineage.attemptLogicalKey(), mapping.mappingLogicalKey(),
                identity.securityLogicalKey(),
                predecessor == null ? null : predecessor.eventLogicalKey(),
                context.run().runNamespace(), SecurityStatusEventPayloadContract.VERSION,
                command.normalizerVersion(), command.transitionRuleVersion(), event.payloadHash(),
                assurance);
        if (lineage.eventId() != event.id()
                || !lineage.eventLogicalKey().equals(event.eventLogicalKey())
                || lineage.datasetVersionId() != context.dataset().id()
                || !lineage.datasetLogicalKey().equals(context.run().datasetLogicalKey())
                || lineage.rawRecordId() != context.raw().id()
                || !lineage.rawRecordLogicalKey().equals(context.raw().logicalKey())
                || lineageRun.datasetVersionId() != context.dataset().id()
                || lineageRun.datasetType() != DatasetType.SECURITY_STATUS
                || lineageRun.runNamespace() != context.run().runNamespace()
                || lineageRun.manifestContractVersion()
                        != ManifestContractVersion.INGESTION_MANIFEST_V2_SECURITY_EVENT
                || !lineageRun.logicalKey().equals(lineage.ingestionRunLogicalKey())
                || lineageRaw.id() != context.raw().id()
                || !lineageRaw.logicalKey().equals(lineage.rawRecordLogicalKey())
                || lineageAttempt.ingestionRunId() != lineageRun.id()
                || lineageAttempt.rawRecordId() != lineageRaw.id()
                || !lineageAttempt.logicalKey().equals(lineage.attemptLogicalKey())
                || lineageAttempt.status() != AttemptStatus.COMPLETED
                || lineage.securityIdentityId() != identity.id()
                || !lineage.securityLogicalKey().equals(identity.securityLogicalKey())
                || lineage.mappingId() != mapping.id()
                || !lineage.mappingLogicalKey().equals(mapping.mappingLogicalKey())
                || !Objects.equals(lineage.predecessorEventId(),
                        predecessor == null ? null : predecessor.id())
                || !Objects.equals(lineage.predecessorEventLogicalKey(),
                        predecessor == null ? null : predecessor.eventLogicalKey())
                || lineage.recordNamespace() != context.run().runNamespace()
                || !lineage.eventContractVersion().equals(
                        SecurityStatusEventPayloadContract.VERSION)
                || !lineage.normalizerVersion().equals(command.normalizerVersion())
                || !lineage.transitionRuleVersion().equals(command.transitionRuleVersion())
                || lineage.assuranceLevel() != assurance
                || !lineage.lineageHash().equals(expectedHash)) {
            throw conflict("event lineage does not match its immutable raw provenance chain");
        }
    }

    private static void verifyResult(
            MaterializeSecurityStatusCommand command,
            ProcessingAttempt attempt,
            NormalizationOutcome outcome,
            MaterializedSecurityEvent event,
            String securityLogicalKey,
            String predecessorEventLogicalKey,
            AssuranceLevel assurance,
            String resultHash,
            String errorCode,
            SecurityStatusNormalizationResult result
    ) {
        if (result.attemptId() != attempt.id()
                || !result.attemptLogicalKey().equals(attempt.logicalKey())
                || result.outcome() != outcome
                || !Objects.equals(result.eventId(), event == null ? null : event.id())
                || !Objects.equals(result.eventLogicalKey(),
                        event == null ? null : event.eventLogicalKey())
                || !Objects.equals(result.securityLogicalKey(), securityLogicalKey)
                || !Objects.equals(result.predecessorEventLogicalKey(),
                        predecessorEventLogicalKey)
                || !result.normalizerVersion().equals(command.normalizerVersion())
                || !result.transitionRuleVersion().equals(command.transitionRuleVersion())
                || result.assuranceLevel() != assurance
                || !result.resultHash().equals(resultHash)
                || !Objects.equals(result.errorCode(), errorCode)) {
            throw conflict("normalization result idempotency resolved to different content");
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

    private static void verifySealed(
            SealRunCommand command,
            String manifestHash,
            int received,
            int accepted,
            int rejected,
            AssuranceLevel assurance,
            IngestionRun value
    ) {
        if (!value.sealed()
                || value.manifestContractVersion()
                != ManifestContractVersion.INGESTION_MANIFEST_V2_SECURITY_EVENT
                || value.status() != command.status()
                || !Objects.equals(value.finalExpectedCount(), command.expectedCount())
                || !Objects.equals(value.finalReceivedCount(), received)
                || !Objects.equals(value.finalAcceptedCount(), accepted)
                || !Objects.equals(value.finalRejectedCount(), rejected)
                || value.assuranceLevel() != assurance
                || !value.manifestHash().equals(manifestHash)) {
            throw conflict("sealed Manifest V2 run does not match computed facts");
        }
    }

    private static IngestionDataConflictException conflict(String message) {
        return new IngestionDataConflictException(message);
    }

    private record Context(
            IngestionRun run,
            RawRecord raw,
            DatasetVersion dataset,
            KnowledgeAssessment assessment
    ) {}

    private record FailureChain(
            String securityLogicalKey,
            String predecessorEventLogicalKey,
            AssuranceLevel assuranceLevel
    ) {
        private static FailureChain unresolved() {
            return new FailureChain(null, null, AssuranceLevel.INFERRED_RESEARCH);
        }
    }
}

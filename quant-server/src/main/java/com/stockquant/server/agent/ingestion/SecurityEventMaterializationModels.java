package com.stockquant.server.agent.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockquant.server.agent.ingestion.IngestionModels.AssuranceLevel;
import com.stockquant.server.agent.ingestion.IngestionModels.AttemptStatus;
import com.stockquant.server.agent.ingestion.IngestionModels.ProcessingAttempt;
import com.stockquant.server.agent.ingestion.IngestionModels.PublicationTimeVerification;
import com.stockquant.server.agent.ingestion.IngestionModels.RunNamespace;
import com.stockquant.server.agent.temporal.SecurityStatusEventType;
import com.stockquant.server.agent.temporal.SecurityStatusEventPayloadContract.SecurityStatusState;
import com.stockquant.server.agent.temporal.TemporalTrustLevel;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Frozen TEST/DEMO contracts for stage 2D-2B-1B-1 event materialization. */
public final class SecurityEventMaterializationModels {

    public static final String RAW_CONTRACT_VERSION = "SECURITY_STATUS_RAW_TEST_V1";
    public static final String IDENTITY_CONTRACT_VERSION = "SECURITY_IDENTITY_V1";
    public static final String MAPPING_CONTRACT_VERSION =
            "SOURCE_SECURITY_IDENTITY_MAPPING_V1";
    public static final String NORMALIZER_VERSION = "SECURITY_STATUS_NORMALIZER_V1";
    public static final String TRANSITION_RULE_VERSION = "SECURITY_STATUS_TRANSITION_V1";
    public static final String RESULT_CONTRACT_VERSION =
            "SECURITY_STATUS_NORMALIZATION_RESULT_V1";
    public static final String LINEAGE_CONTRACT_VERSION =
            "SECURITY_STATUS_EVENT_LINEAGE_V1";

    private SecurityEventMaterializationModels() {}

    public enum NormalizationOutcome {
        EVENT_MATERIALIZED,
        EVENT_REUSED,
        NO_STATE_CHANGE,
        IDENTITY_UNRESOLVED,
        UNSUPPORTED_CONTRACT,
        CONFLICT,
        PROJECTION_FAILED,
        REJECTED;

        public AttemptStatus requiredAttemptStatus() {
            return switch (this) {
                case EVENT_MATERIALIZED, EVENT_REUSED, NO_STATE_CHANGE ->
                        AttemptStatus.COMPLETED;
                case IDENTITY_UNRESOLVED -> AttemptStatus.IDENTITY_UNRESOLVED;
                case UNSUPPORTED_CONTRACT -> AttemptStatus.UNSUPPORTED_CONTRACT;
                case CONFLICT -> AttemptStatus.CONFLICT;
                case PROJECTION_FAILED -> AttemptStatus.PROJECTION_FAILED;
                case REJECTED -> AttemptStatus.REJECTED;
            };
        }

        public boolean referencesEvent() {
            return this == EVENT_MATERIALIZED || this == EVENT_REUSED;
        }
    }

    public record RegisterSecurityIdentityCommand(
            RunNamespace recordNamespace,
            String identityAuthority,
            String identityStableId,
            String identityContractVersion,
            AssuranceLevel assuranceLevel
    ) {
        public RegisterSecurityIdentityCommand {
            recordNamespace = testOrDemo(recordNamespace);
            identityAuthority = IngestionValidation.text(
                    identityAuthority, "identityAuthority", 128);
            identityStableId = IngestionValidation.text(
                    identityStableId, "identityStableId", 256);
            identityContractVersion = IngestionValidation.text(
                    identityContractVersion, "identityContractVersion", 80);
            assuranceLevel = stageAssurance(assuranceLevel, "assuranceLevel");
        }
    }

    public record SecurityIdentity(
            long id,
            String securityLogicalKey,
            RunNamespace recordNamespace,
            String identityAuthority,
            String identityStableId,
            String identityContractVersion,
            AssuranceLevel assuranceLevel,
            Instant recordedAt
    ) {
        public SecurityIdentity {
            id = IngestionValidation.positiveId(id, "id");
            securityLogicalKey = IngestionValidation.text(
                    securityLogicalKey, "securityLogicalKey", 256);
            recordNamespace = testOrDemo(recordNamespace);
            identityAuthority = IngestionValidation.text(
                    identityAuthority, "identityAuthority", 128);
            identityStableId = IngestionValidation.text(
                    identityStableId, "identityStableId", 256);
            identityContractVersion = IngestionValidation.text(
                    identityContractVersion, "identityContractVersion", 80);
            assuranceLevel = stageAssurance(assuranceLevel, "assuranceLevel");
            recordedAt = IngestionValidation.instant(recordedAt, "recordedAt");
        }
    }

    public record RegisterSourceSecurityIdentityMappingCommand(
            RunNamespace recordNamespace,
            String source,
            String sourceVersion,
            String sourceInstrumentId,
            String securityLogicalKey,
            String mappingContractVersion,
            AssuranceLevel mappingAssuranceLevel
    ) {
        public RegisterSourceSecurityIdentityMappingCommand {
            recordNamespace = testOrDemo(recordNamespace);
            source = IngestionValidation.text(source, "source", 128);
            sourceVersion = IngestionValidation.text(sourceVersion, "sourceVersion", 128);
            sourceInstrumentId = IngestionValidation.text(
                    sourceInstrumentId, "sourceInstrumentId", 256);
            securityLogicalKey = IngestionValidation.text(
                    securityLogicalKey, "securityLogicalKey", 256);
            mappingContractVersion = IngestionValidation.text(
                    mappingContractVersion, "mappingContractVersion", 80);
            mappingAssuranceLevel = stageAssurance(
                    mappingAssuranceLevel, "mappingAssuranceLevel");
        }
    }

    public record SourceSecurityIdentityMapping(
            long id,
            String mappingLogicalKey,
            RunNamespace recordNamespace,
            String source,
            String sourceVersion,
            String sourceInstrumentId,
            long securityIdentityId,
            String securityLogicalKey,
            String mappingContractVersion,
            AssuranceLevel mappingAssuranceLevel,
            Instant recordedAt
    ) {
        public SourceSecurityIdentityMapping {
            id = IngestionValidation.positiveId(id, "id");
            mappingLogicalKey = IngestionValidation.text(
                    mappingLogicalKey, "mappingLogicalKey", 256);
            recordNamespace = testOrDemo(recordNamespace);
            source = IngestionValidation.text(source, "source", 128);
            sourceVersion = IngestionValidation.text(sourceVersion, "sourceVersion", 128);
            sourceInstrumentId = IngestionValidation.text(
                    sourceInstrumentId, "sourceInstrumentId", 256);
            securityIdentityId = IngestionValidation.positiveId(
                    securityIdentityId, "securityIdentityId");
            securityLogicalKey = IngestionValidation.text(
                    securityLogicalKey, "securityLogicalKey", 256);
            mappingContractVersion = IngestionValidation.text(
                    mappingContractVersion, "mappingContractVersion", 80);
            mappingAssuranceLevel = stageAssurance(
                    mappingAssuranceLevel, "mappingAssuranceLevel");
            recordedAt = IngestionValidation.instant(recordedAt, "recordedAt");
        }
    }

    public record MaterializeSecurityStatusCommand(
            long ingestionRunId,
            long rawRecordId,
            int attemptNo,
            PublicationTimeVerification publicationTimeVerification,
            AssuranceLevel requestedAssuranceLevel,
            String processorVersion,
            String rawContractVersion,
            String normalizerVersion,
            String transitionRuleVersion
    ) {
        public MaterializeSecurityStatusCommand {
            ingestionRunId = IngestionValidation.positiveId(
                    ingestionRunId, "ingestionRunId");
            rawRecordId = IngestionValidation.positiveId(rawRecordId, "rawRecordId");
            if (attemptNo <= 0) throw new IllegalArgumentException("attemptNo must be positive");
            publicationTimeVerification = IngestionValidation.required(
                    publicationTimeVerification, "publicationTimeVerification");
            requestedAssuranceLevel = IngestionValidation.required(
                    requestedAssuranceLevel, "requestedAssuranceLevel");
            processorVersion = IngestionValidation.text(
                    processorVersion, "processorVersion", 120);
            rawContractVersion = IngestionValidation.text(
                    rawContractVersion, "rawContractVersion", 120);
            normalizerVersion = IngestionValidation.text(
                    normalizerVersion, "normalizerVersion", 120);
            transitionRuleVersion = IngestionValidation.text(
                    transitionRuleVersion, "transitionRuleVersion", 120);
        }
    }

    public record ParsedSecurityStatusRaw(String symbol, SecurityStatusState state) {
        public ParsedSecurityStatusRaw {
            symbol = IngestionValidation.text(symbol, "symbol", 12);
            state = IngestionValidation.required(state, "state");
            requireStateInvariant(state);
        }
    }

    public record MaterializedSecurityEvent(
            long id,
            long datasetVersionId,
            String symbol,
            SecurityStatusEventType eventType,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Instant publishedAt,
            Instant knownAt,
            Instant recordedAt,
            String source,
            String sourceVersion,
            String sourceRecordId,
            String sourceRevision,
            TemporalTrustLevel trustLevel,
            JsonNode payload,
            String payloadHash,
            Long predecessorEventId,
            String eventLogicalKey,
            String eventContractVersion,
            RunNamespace recordNamespace,
            AssuranceLevel assuranceLevel,
            String securityLogicalKey
    ) {
        public MaterializedSecurityEvent {
            id = IngestionValidation.positiveId(id, "id");
            datasetVersionId = IngestionValidation.positiveId(
                    datasetVersionId, "datasetVersionId");
            symbol = IngestionValidation.text(symbol, "symbol", 12);
            eventType = IngestionValidation.required(eventType, "eventType");
            effectiveFrom = IngestionValidation.required(effectiveFrom, "effectiveFrom");
            if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
                throw new IllegalArgumentException("effectiveTo must be after effectiveFrom");
            }
            publishedAt = IngestionValidation.optionalInstant(publishedAt);
            knownAt = IngestionValidation.instant(knownAt, "knownAt");
            recordedAt = IngestionValidation.instant(recordedAt, "recordedAt");
            source = IngestionValidation.text(source, "source", 128);
            sourceVersion = IngestionValidation.text(sourceVersion, "sourceVersion", 128);
            sourceRecordId = IngestionValidation.text(sourceRecordId, "sourceRecordId", 256);
            sourceRevision = IngestionValidation.text(sourceRevision, "sourceRevision", 128);
            trustLevel = IngestionValidation.required(trustLevel, "trustLevel");
            payload = IngestionValidation.object(payload, "payload");
            payloadHash = IngestionValidation.sha256(payloadHash, "payloadHash");
            if (predecessorEventId != null) {
                predecessorEventId = IngestionValidation.positiveId(
                        predecessorEventId, "predecessorEventId");
            }
            eventLogicalKey = IngestionValidation.text(
                    eventLogicalKey, "eventLogicalKey", 256);
            eventContractVersion = IngestionValidation.text(
                    eventContractVersion, "eventContractVersion", 120);
            recordNamespace = testOrDemo(recordNamespace);
            assuranceLevel = stageAssurance(assuranceLevel, "assuranceLevel");
            securityLogicalKey = IngestionValidation.text(
                    securityLogicalKey, "securityLogicalKey", 256);
        }
    }

    public record SecurityStatusEventLineage(
            long id,
            long eventId,
            String eventLogicalKey,
            long datasetVersionId,
            String datasetLogicalKey,
            long rawRecordId,
            String rawRecordLogicalKey,
            long ingestionRunId,
            String ingestionRunLogicalKey,
            long processingAttemptId,
            String attemptLogicalKey,
            long securityIdentityId,
            String securityLogicalKey,
            long mappingId,
            String mappingLogicalKey,
            Long predecessorEventId,
            String predecessorEventLogicalKey,
            RunNamespace recordNamespace,
            String eventContractVersion,
            String normalizerVersion,
            String transitionRuleVersion,
            AssuranceLevel assuranceLevel,
            String lineageHash,
            Instant recordedAt
    ) {
        public SecurityStatusEventLineage {
            id = IngestionValidation.positiveId(id, "id");
            eventId = IngestionValidation.positiveId(eventId, "eventId");
            eventLogicalKey = IngestionValidation.text(
                    eventLogicalKey, "eventLogicalKey", 256);
            datasetVersionId = IngestionValidation.positiveId(
                    datasetVersionId, "datasetVersionId");
            datasetLogicalKey = IngestionValidation.text(
                    datasetLogicalKey, "datasetLogicalKey", 256);
            rawRecordId = IngestionValidation.positiveId(rawRecordId, "rawRecordId");
            rawRecordLogicalKey = IngestionValidation.text(
                    rawRecordLogicalKey, "rawRecordLogicalKey", 256);
            ingestionRunId = IngestionValidation.positiveId(ingestionRunId, "ingestionRunId");
            ingestionRunLogicalKey = IngestionValidation.text(
                    ingestionRunLogicalKey, "ingestionRunLogicalKey", 256);
            processingAttemptId = IngestionValidation.positiveId(
                    processingAttemptId, "processingAttemptId");
            attemptLogicalKey = IngestionValidation.text(
                    attemptLogicalKey, "attemptLogicalKey", 256);
            securityIdentityId = IngestionValidation.positiveId(
                    securityIdentityId, "securityIdentityId");
            securityLogicalKey = IngestionValidation.text(
                    securityLogicalKey, "securityLogicalKey", 256);
            mappingId = IngestionValidation.positiveId(mappingId, "mappingId");
            mappingLogicalKey = IngestionValidation.text(
                    mappingLogicalKey, "mappingLogicalKey", 256);
            if (predecessorEventId != null) {
                predecessorEventId = IngestionValidation.positiveId(
                        predecessorEventId, "predecessorEventId");
            }
            predecessorEventLogicalKey = IngestionValidation.optionalText(
                    predecessorEventLogicalKey, "predecessorEventLogicalKey", 256);
            recordNamespace = testOrDemo(recordNamespace);
            eventContractVersion = IngestionValidation.text(
                    eventContractVersion, "eventContractVersion", 120);
            normalizerVersion = IngestionValidation.text(
                    normalizerVersion, "normalizerVersion", 120);
            transitionRuleVersion = IngestionValidation.text(
                    transitionRuleVersion, "transitionRuleVersion", 120);
            assuranceLevel = stageAssurance(assuranceLevel, "assuranceLevel");
            lineageHash = IngestionValidation.sha256(lineageHash, "lineageHash");
            recordedAt = IngestionValidation.instant(recordedAt, "recordedAt");
        }
    }

    public record SecurityStatusNormalizationResult(
            long id,
            long attemptId,
            String attemptLogicalKey,
            NormalizationOutcome outcome,
            Long eventId,
            String eventLogicalKey,
            String securityLogicalKey,
            String predecessorEventLogicalKey,
            String normalizerVersion,
            String transitionRuleVersion,
            AssuranceLevel assuranceLevel,
            String resultHash,
            String errorCode,
            Instant recordedAt
    ) {
        public SecurityStatusNormalizationResult {
            id = IngestionValidation.positiveId(id, "id");
            attemptId = IngestionValidation.positiveId(attemptId, "attemptId");
            attemptLogicalKey = IngestionValidation.text(
                    attemptLogicalKey, "attemptLogicalKey", 256);
            outcome = IngestionValidation.required(outcome, "outcome");
            if (eventId != null) eventId = IngestionValidation.positiveId(eventId, "eventId");
            eventLogicalKey = IngestionValidation.optionalText(
                    eventLogicalKey, "eventLogicalKey", 256);
            securityLogicalKey = IngestionValidation.optionalText(
                    securityLogicalKey, "securityLogicalKey", 256);
            predecessorEventLogicalKey = IngestionValidation.optionalText(
                    predecessorEventLogicalKey, "predecessorEventLogicalKey", 256);
            normalizerVersion = IngestionValidation.text(
                    normalizerVersion, "normalizerVersion", 120);
            transitionRuleVersion = IngestionValidation.text(
                    transitionRuleVersion, "transitionRuleVersion", 120);
            assuranceLevel = stageAssurance(assuranceLevel, "assuranceLevel");
            resultHash = IngestionValidation.sha256(resultHash, "resultHash");
            errorCode = IngestionValidation.optionalText(errorCode, "errorCode", 120);
            recordedAt = IngestionValidation.instant(recordedAt, "recordedAt");
            if (outcome.referencesEvent() != (eventId != null && eventLogicalKey != null)) {
                throw new IllegalArgumentException("normalization event reference does not match outcome");
            }
        }
    }

    public record MaterializationResult(
            ProcessingAttempt attempt,
            SecurityStatusNormalizationResult normalizationResult,
            MaterializedSecurityEvent event,
            SecurityStatusEventLineage lineage
    ) {
        public MaterializationResult {
            attempt = IngestionValidation.required(attempt, "attempt");
            normalizationResult = IngestionValidation.required(
                    normalizationResult, "normalizationResult");
            if (normalizationResult.outcome().referencesEvent() && event == null) {
                throw new IllegalArgumentException("event outcome requires event");
            }
        }
    }

    record SecurityEventManifestEntry(
            String rawRecordLogicalKey,
            String rawPayloadHash,
            TemporalTrustLevel rawSourceTrustLevel,
            String sourceInstrumentId,
            int attemptNo,
            String attemptLogicalKey,
            AttemptStatus attemptStatus,
            String processorVersion,
            String contractVersion,
            PublicationTimeVerification publicationTimeVerification,
            AssuranceLevel requestedAssuranceLevel,
            String knowledgeTimePolicyVersion,
            AssuranceLevel attemptAssuranceLevel,
            Instant derivedKnownFrom,
            String attemptErrorCode,
            String attemptResultHash,
            NormalizationOutcome outcome,
            String eventLogicalKey,
            SecurityStatusEventType eventType,
            String eventPayloadHash,
            AssuranceLevel eventAssuranceLevel,
            String securityLogicalKey,
            String predecessorEventLogicalKey,
            RunNamespace recordNamespace,
            String normalizerVersion,
            String transitionRuleVersion,
            AssuranceLevel resultAssuranceLevel,
            String normalizationResultHash,
            String lineageHash
    ) {}

    static RunNamespace testOrDemo(RunNamespace namespace) {
        namespace = IngestionValidation.required(namespace, "recordNamespace");
        if (namespace == RunNamespace.FORMAL) {
            throw new IllegalArgumentException("FORMAL security identity is not approved");
        }
        return namespace;
    }

    static AssuranceLevel stageAssurance(AssuranceLevel assurance, String field) {
        assurance = IngestionValidation.required(assurance, field);
        if (assurance == AssuranceLevel.PIT_VERIFIED) {
            throw new IllegalArgumentException(field + " cannot be PIT_VERIFIED in TEST/DEMO");
        }
        return assurance;
    }

    static void requireStateInvariant(SecurityStatusState state) {
        if (state.active() && !state.listed()) {
            throw new IllegalArgumentException("active=true requires listed=true");
        }
        if (!state.listed() && state.active()) {
            throw new IllegalArgumentException("listed=false requires active=false");
        }
    }

    static AssuranceLevel conservative(AssuranceLevel first, AssuranceLevel... remaining) {
        AssuranceLevel value = IngestionValidation.required(first, "assuranceLevel");
        for (AssuranceLevel next : remaining) value = value.conservativeWith(next);
        return value.conservativeWith(AssuranceLevel.RECONSTRUCTED_VERIFIED);
    }

    static List<SecurityEventManifestEntry> immutableEntries(
            List<SecurityEventManifestEntry> entries
    ) {
        return List.copyOf(IngestionValidation.required(entries, "entries"));
    }
}

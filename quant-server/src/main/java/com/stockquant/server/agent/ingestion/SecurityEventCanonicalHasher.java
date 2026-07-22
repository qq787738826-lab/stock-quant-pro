package com.stockquant.server.agent.ingestion;

import com.stockquant.server.agent.ingestion.IngestionModels.AssuranceLevel;
import com.stockquant.server.agent.ingestion.IngestionModels.IngestionRun;
import com.stockquant.server.agent.ingestion.IngestionModels.RunNamespace;
import com.stockquant.server.agent.ingestion.IngestionModels.RunStatus;
import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels.NormalizationOutcome;
import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels.SecurityEventManifestEntry;
import com.stockquant.server.agent.temporal.SecurityStatusEventType;
import com.stockquant.server.agent.temporal.TemporalModels.DatasetVersion;

import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Canonical V1 logical identities and Manifest V2 hashes for event materialization. */
@Component
public class SecurityEventCanonicalHasher {

    public static final String MANIFEST_VERSION = "INGESTION_MANIFEST_V2_SECURITY_EVENT";
    private static final DateTimeFormatter MICRO_INSTANT =
            new java.time.format.DateTimeFormatterBuilder().appendInstant(6).toFormatter();

    public String securityLogicalKey(
            RunNamespace namespace,
            String identityAuthority,
            String identityStableId,
            String identityContractVersion
    ) {
        return logicalKey("security:v1:", "SECURITY_IDENTITY_LOGICAL_KEY_V1",
                namespace.name(), identityAuthority, identityStableId, identityContractVersion);
    }

    public String mappingLogicalKey(
            RunNamespace namespace,
            String source,
            String sourceVersion,
            String sourceInstrumentId,
            String mappingContractVersion
    ) {
        return logicalKey("mapping:v1:", "SOURCE_SECURITY_IDENTITY_MAPPING_LOGICAL_KEY_V1",
                namespace.name(), source, sourceVersion, sourceInstrumentId,
                mappingContractVersion);
    }

    public String eventLogicalKey(
            String rawRecordLogicalKey,
            String eventContractVersion,
            SecurityStatusEventType eventType
    ) {
        return logicalKey("event:v1:", "SECURITY_STATUS_EVENT_LOGICAL_KEY_V1",
                rawRecordLogicalKey, eventContractVersion, eventType.name());
    }

    public String normalizationResultHash(
            String attemptLogicalKey,
            NormalizationOutcome outcome,
            String eventLogicalKey,
            String securityLogicalKey,
            String predecessorEventLogicalKey,
            String normalizerVersion,
            String transitionRuleVersion,
            AssuranceLevel assuranceLevel,
            String errorCode
    ) {
        return hash("SECURITY_STATUS_NORMALIZATION_RESULT_V1", attemptLogicalKey,
                outcome.name(), eventLogicalKey, securityLogicalKey,
                predecessorEventLogicalKey, normalizerVersion, transitionRuleVersion,
                assuranceLevel.name(), errorCode);
    }

    public String lineageHash(
            String eventLogicalKey,
            String datasetLogicalKey,
            String rawRecordLogicalKey,
            String ingestionRunLogicalKey,
            String attemptLogicalKey,
            String mappingLogicalKey,
            String securityLogicalKey,
            String predecessorEventLogicalKey,
            RunNamespace namespace,
            String eventContractVersion,
            String normalizerVersion,
            String transitionRuleVersion,
            String eventPayloadHash,
            AssuranceLevel assuranceLevel
    ) {
        return hash("SECURITY_STATUS_EVENT_LINEAGE_V1", eventLogicalKey, datasetLogicalKey,
                rawRecordLogicalKey, ingestionRunLogicalKey, attemptLogicalKey,
                mappingLogicalKey, securityLogicalKey, predecessorEventLogicalKey,
                namespace.name(), eventContractVersion, normalizerVersion,
                transitionRuleVersion, eventPayloadHash, assuranceLevel.name());
    }

    public String manifestV2Hash(
            IngestionRun run,
            DatasetVersion dataset,
            RunStatus terminalStatus,
            int expectedCount,
            int receivedCount,
            int acceptedCount,
            int rejectedCount,
            AssuranceLevel assurance,
            List<SecurityEventManifestEntry> entries
    ) {
        List<SecurityEventManifestEntry> normalized = validateAndSort(entries);
        MessageDigest digest = digest();
        field(digest, IngestionCanonicalHasher.CANONICAL_VERSION);
        field(digest, MANIFEST_VERSION);
        field(digest, run.logicalKey());
        field(digest, run.datasetLogicalKey());
        field(digest, run.datasetType().name());
        field(digest, run.runNamespace().name());
        field(digest, run.operationType().name());
        field(digest, run.requestKey());
        field(digest, run.rootRequestLogicalKey());
        field(digest, Integer.toString(run.runAttemptNumber()));
        field(digest, run.requestedRangeStart().toString());
        field(digest, run.requestedRangeEnd().toString());
        field(digest, run.retryOfRunLogicalKey());
        field(digest, terminalStatus.name());
        field(digest, dataset.source());
        field(digest, dataset.sourceVersion());
        field(digest, dataset.connectorVersion());
        field(digest, dataset.trustLevel().name());
        field(digest, Integer.toString(expectedCount));
        field(digest, Integer.toString(receivedCount));
        field(digest, Integer.toString(acceptedCount));
        field(digest, Integer.toString(rejectedCount));
        field(digest, assurance.name());
        field(digest, Integer.toString(normalized.size()));
        for (SecurityEventManifestEntry entry : normalized) append(digest, entry);
        return hex(digest);
    }

    private static void append(MessageDigest digest, SecurityEventManifestEntry entry) {
        field(digest, entry.rawRecordLogicalKey());
        field(digest, entry.rawPayloadHash());
        field(digest, entry.rawSourceTrustLevel().name());
        field(digest, entry.sourceInstrumentId());
        field(digest, null); // exchange is a calendar-only V1 field
        field(digest, null); // tradeDate is a calendar-only V1 field
        field(digest, Integer.toString(entry.attemptNo()));
        field(digest, entry.attemptLogicalKey());
        field(digest, entry.attemptStatus().name());
        field(digest, entry.processorVersion());
        field(digest, entry.contractVersion());
        field(digest, entry.publicationTimeVerification().name());
        field(digest, entry.requestedAssuranceLevel().name());
        field(digest, entry.knowledgeTimePolicyVersion());
        field(digest, entry.attemptAssuranceLevel().name());
        field(digest, instant(entry.derivedKnownFrom()));
        field(digest, entry.attemptErrorCode());
        field(digest, entry.attemptResultHash());
        field(digest, entry.outcome().name());
        field(digest, entry.eventLogicalKey());
        field(digest, entry.eventType() == null ? null : entry.eventType().name());
        field(digest, entry.eventPayloadHash());
        field(digest, entry.eventAssuranceLevel() == null
                ? null : entry.eventAssuranceLevel().name());
        field(digest, entry.securityLogicalKey());
        field(digest, entry.predecessorEventLogicalKey());
        field(digest, entry.recordNamespace().name());
        field(digest, entry.normalizerVersion());
        field(digest, entry.transitionRuleVersion());
        field(digest, entry.resultAssuranceLevel().name());
        field(digest, entry.normalizationResultHash());
        field(digest, entry.lineageHash());
    }

    private static List<SecurityEventManifestEntry> validateAndSort(
            List<SecurityEventManifestEntry> entries
    ) {
        if (entries == null) throw new IllegalArgumentException("manifest entries must not be null");
        Set<String> keys = new HashSet<>();
        List<SecurityEventManifestEntry> values = new ArrayList<>(entries.size());
        for (SecurityEventManifestEntry entry : entries) {
            IngestionValidation.required(entry, "manifestEntry");
            if (!keys.add(entry.attemptLogicalKey())) {
                throw new IllegalArgumentException("duplicate attempt logical key in manifest");
            }
            values.add(entry);
        }
        values.sort(Comparator.comparing(SecurityEventManifestEntry::rawRecordLogicalKey)
                .thenComparingInt(SecurityEventManifestEntry::attemptNo)
                .thenComparing(SecurityEventManifestEntry::attemptLogicalKey));
        return List.copyOf(values);
    }

    private static String logicalKey(String prefix, String contract, String... values) {
        return prefix + hash(contract, values);
    }

    private static String hash(String contract, String... values) {
        MessageDigest digest = digest();
        field(digest, IngestionCanonicalHasher.CANONICAL_VERSION);
        field(digest, contract);
        for (String value : values) field(digest, value);
        return hex(digest);
    }

    private static String instant(Instant value) {
        return MICRO_INSTANT.format(IngestionValidation.instant(value, "instant"));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static void field(MessageDigest digest, String value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static String hex(MessageDigest digest) {
        return HexFormat.of().formatHex(digest.digest());
    }
}

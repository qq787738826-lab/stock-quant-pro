package com.stockquant.server.agent.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockquant.server.agent.ingestion.IngestionModels.AssuranceLevel;
import com.stockquant.server.agent.ingestion.IngestionModels.DatasetType;
import com.stockquant.server.agent.ingestion.IngestionModels.IngestionRun;
import com.stockquant.server.agent.ingestion.IngestionModels.ManifestEntry;
import com.stockquant.server.agent.ingestion.IngestionModels.OperationType;
import com.stockquant.server.agent.ingestion.IngestionModels.RunNamespace;
import com.stockquant.server.agent.ingestion.IngestionModels.RunStatus;
import com.stockquant.server.agent.temporal.TemporalModels.DatasetVersion;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Versioned, length-prefixed SHA-256 identities for the source-neutral ingestion layer. */
@Component
public class IngestionCanonicalHasher {

    static final String CANONICAL_VERSION = "INGESTION_CANONICAL_V1";
    static final String MANIFEST_VERSION = "INGESTION_MANIFEST_V1";
    private static final DateTimeFormatter MICRO_INSTANT =
            new java.time.format.DateTimeFormatterBuilder().appendInstant(6).toFormatter();

    public String datasetLogicalKey(DatasetVersion dataset) {
        MessageDigest digest = digest();
        field(digest, CANONICAL_VERSION);
        field(digest, "DATASET_LOGICAL_KEY_V1");
        field(digest, dataset.datasetType());
        field(digest, dataset.source());
        field(digest, dataset.sourceVersion());
        field(digest, dataset.connectorVersion());
        field(digest, dataset.rangeStart().toString());
        field(digest, dataset.rangeEnd().toString());
        field(digest, dataset.payloadHash());
        return "dataset:v1:" + hex(digest);
    }

    public String rootRequestLogicalKey(
            String datasetLogicalKey,
            DatasetType datasetType,
            RunNamespace namespace,
            OperationType operationType,
            String requestKey,
            LocalDate requestedRangeStart,
            LocalDate requestedRangeEnd
    ) {
        MessageDigest digest = digest();
        field(digest, CANONICAL_VERSION);
        field(digest, "INGESTION_ROOT_REQUEST_LOGICAL_KEY_V1");
        field(digest, datasetLogicalKey);
        field(digest, datasetType.name());
        field(digest, namespace.name());
        field(digest, operationType.name());
        field(digest, requestKey);
        field(digest, requestedRangeStart.toString());
        field(digest, requestedRangeEnd.toString());
        return "root-request:v1:" + hex(digest);
    }

    public String runLogicalKey(
            String datasetLogicalKey,
            DatasetType datasetType,
            RunNamespace namespace,
            OperationType operationType,
            String requestKey,
            String rootRequestLogicalKey,
            int runAttemptNumber,
            LocalDate requestedRangeStart,
            LocalDate requestedRangeEnd,
            String retryOfRunLogicalKey
    ) {
        if (runAttemptNumber <= 0) {
            throw new IllegalArgumentException("runAttemptNumber must be positive");
        }
        MessageDigest digest = digest();
        field(digest, CANONICAL_VERSION);
        field(digest, "INGESTION_RUN_LOGICAL_KEY_V1");
        field(digest, datasetLogicalKey);
        field(digest, datasetType.name());
        field(digest, namespace.name());
        field(digest, operationType.name());
        field(digest, requestKey);
        field(digest, rootRequestLogicalKey);
        field(digest, Integer.toString(runAttemptNumber));
        field(digest, requestedRangeStart.toString());
        field(digest, requestedRangeEnd.toString());
        field(digest, retryOfRunLogicalKey);
        return "run:v1:" + hex(digest);
    }

    public String rawRecordLogicalKey(
            DatasetType datasetType,
            RunNamespace namespace,
            String source,
            String sourceVersion,
            String sourceRecordId,
            String sourceRevision
    ) {
        MessageDigest digest = digest();
        field(digest, CANONICAL_VERSION);
        field(digest, "RAW_RECORD_LOGICAL_KEY_V1");
        field(digest, datasetType.name());
        field(digest, namespace.name());
        field(digest, source);
        field(digest, sourceVersion);
        field(digest, sourceRecordId);
        field(digest, sourceRevision);
        return "raw:v1:" + hex(digest);
    }

    public String attemptLogicalKey(
            String runLogicalKey,
            String rawRecordLogicalKey,
            int attemptNo,
            String processorVersion,
            String contractVersion
    ) {
        if (attemptNo <= 0) throw new IllegalArgumentException("attemptNo must be positive");
        MessageDigest digest = digest();
        field(digest, CANONICAL_VERSION);
        field(digest, "PROCESSING_ATTEMPT_LOGICAL_KEY_V1");
        field(digest, runLogicalKey);
        field(digest, rawRecordLogicalKey);
        field(digest, Integer.toString(attemptNo));
        field(digest, processorVersion);
        field(digest, contractVersion);
        return "attempt:v1:" + hex(digest);
    }

    public String jsonHash(JsonNode value) {
        MessageDigest digest = digest();
        field(digest, CANONICAL_VERSION);
        json(digest, IngestionValidation.required(value, "json"));
        return hex(digest);
    }

    public String manifestHash(
            IngestionRun run,
            DatasetVersion dataset,
            RunStatus terminalStatus,
            int expectedCount,
            int receivedCount,
            int acceptedCount,
            int rejectedCount,
            AssuranceLevel assurance,
            List<ManifestEntry> entries
    ) {
        List<ManifestEntry> normalized = validateAndSort(entries);
        MessageDigest digest = digest();
        field(digest, CANONICAL_VERSION);
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
        field(digest, IngestionValidation.required(terminalStatus, "terminalStatus").name());
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
        for (ManifestEntry entry : normalized) {
            field(digest, entry.rawRecordLogicalKey());
            field(digest, entry.rawPayloadHash());
            field(digest, entry.rawSourceTrustLevel().name());
            field(digest, entry.sourceInstrumentId());
            field(digest, entry.exchange());
            field(digest, entry.tradeDate() == null ? null : entry.tradeDate().toString());
            field(digest, Integer.toString(entry.attemptNo()));
            field(digest, entry.attemptLogicalKey());
            field(digest, entry.attemptStatus().name());
            field(digest, entry.processorVersion());
            field(digest, entry.contractVersion());
            field(digest, entry.publicationTimeVerification().name());
            field(digest, entry.requestedAssuranceLevel().name());
            field(digest, entry.knowledgeTimePolicyVersion());
            field(digest, entry.assuranceLevel().name());
            field(digest, instant(entry.derivedKnownFrom()));
            field(digest, entry.errorCode());
            field(digest, entry.resultHash());
        }
        return hex(digest);
    }

    private static List<ManifestEntry> validateAndSort(List<ManifestEntry> entries) {
        if (entries == null) throw new IllegalArgumentException("manifest entries must not be null");
        Set<String> attemptKeys = new HashSet<>();
        List<ManifestEntry> result = new ArrayList<>(entries.size());
        for (ManifestEntry entry : entries) {
            IngestionValidation.required(entry, "manifestEntry");
            if (!attemptKeys.add(entry.attemptLogicalKey())) {
                throw new IllegalArgumentException("duplicate attempt logical key in manifest");
            }
            result.add(entry);
        }
        result.sort(Comparator
                .comparing(ManifestEntry::rawRecordLogicalKey)
                .thenComparingInt(ManifestEntry::attemptNo)
                .thenComparing(ManifestEntry::attemptLogicalKey));
        return List.copyOf(result);
    }

    private static void json(MessageDigest digest, JsonNode node) {
        if (node.isNull()) {
            field(digest, "NULL");
        } else if (node.isObject()) {
            field(digest, "OBJECT");
            TreeSet<String> names = new TreeSet<>(IngestionCanonicalHasher::compareUtf8);
            node.fieldNames().forEachRemaining(names::add);
            field(digest, Integer.toString(names.size()));
            for (String name : names) {
                field(digest, name);
                json(digest, node.get(name));
            }
        } else if (node.isArray()) {
            field(digest, "ARRAY");
            field(digest, Integer.toString(node.size()));
            node.forEach(item -> json(digest, item));
        } else if (node.isBoolean()) {
            field(digest, "BOOLEAN");
            field(digest, node.booleanValue() ? "1" : "0");
        } else if (node.isNumber()) {
            BigDecimal stable = node.decimalValue().stripTrailingZeros();
            if (stable.signum() == 0) stable = BigDecimal.ZERO;
            field(digest, "NUMBER");
            field(digest, stable.toPlainString());
        } else if (node.isTextual()) {
            field(digest, "STRING");
            field(digest, node.textValue());
        } else {
            throw new IllegalArgumentException("unsupported JSON node type: " + node.getNodeType());
        }
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

    private static int compareUtf8(String first, String second) {
        byte[] left = first.getBytes(StandardCharsets.UTF_8);
        byte[] right = second.getBytes(StandardCharsets.UTF_8);
        int common = Math.min(left.length, right.length);
        for (int index = 0; index < common; index++) {
            int comparison = Integer.compare(Byte.toUnsignedInt(left[index]),
                    Byte.toUnsignedInt(right[index]));
            if (comparison != 0) return comparison;
        }
        return Integer.compare(left.length, right.length);
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

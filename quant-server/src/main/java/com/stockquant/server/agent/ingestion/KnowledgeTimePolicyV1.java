package com.stockquant.server.agent.ingestion;

import com.stockquant.server.agent.ingestion.IngestionModels.AssuranceLevel;
import com.stockquant.server.agent.ingestion.IngestionModels.KnowledgeAssessment;
import com.stockquant.server.agent.ingestion.IngestionModels.PublicationTimeVerification;
import com.stockquant.server.agent.ingestion.IngestionModels.RawRecord;
import com.stockquant.server.agent.temporal.TemporalTrustLevel;

import org.springframework.stereotype.Component;

import java.time.Instant;

/** Java-authoritative knowledge-time derivation for source-neutral processing attempts. */
@Component
public final class KnowledgeTimePolicyV1 {

    public static final String VERSION = "KNOWLEDGE_TIME_POLICY_V1";

    public KnowledgeAssessment assess(
            RawRecord raw,
            TemporalTrustLevel datasetTrustLevel,
            PublicationTimeVerification verification,
            AssuranceLevel requested
    ) {
        IngestionValidation.required(raw, "raw");
        IngestionValidation.required(datasetTrustLevel, "datasetTrustLevel");
        IngestionValidation.required(verification, "publicationTimeVerification");
        IngestionValidation.required(requested, "requestedAssuranceLevel");

        Instant publishedAt = raw.sourcePublishedAt();
        switch (verification) {
            case VERIFIED -> {
                if (publishedAt == null) {
                    throw new IllegalArgumentException(
                            "VERIFIED publication time requires sourcePublishedAt");
                }
            }
            case UNVERIFIED -> {
                if (publishedAt == null) {
                    throw new IllegalArgumentException(
                            "UNVERIFIED publication time requires sourcePublishedAt");
                }
            }
            case NOT_PROVIDED -> {
                if (publishedAt != null) {
                    throw new IllegalArgumentException(
                            "NOT_PROVIDED publication time requires sourcePublishedAt to be null");
                }
            }
        }

        AssuranceLevel policyMaximum;
        TemporalTrustLevel conservativeTrust = TemporalTrustLevel.mostConservative(
                datasetTrustLevel, raw.sourceTrustLevel());
        policyMaximum = switch (conservativeTrust) {
            case BACKFILLED_INFERRED -> AssuranceLevel.INFERRED_RESEARCH;
            case BACKFILLED_VERIFIED -> AssuranceLevel.RECONSTRUCTED_VERIFIED;
            case OBSERVED -> verification == PublicationTimeVerification.VERIFIED
                    ? AssuranceLevel.PIT_VERIFIED
                    : AssuranceLevel.RECONSTRUCTED_VERIFIED;
        };

        Instant derivedKnownFrom = verification == PublicationTimeVerification.VERIFIED
                ? publishedAt : raw.systemFirstObservedAt();
        return new KnowledgeAssessment(
                IngestionValidation.instant(derivedKnownFrom, "derivedKnownFrom"),
                requested.conservativeWith(policyMaximum));
    }
}

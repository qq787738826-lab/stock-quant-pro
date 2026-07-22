package com.stockquant.server.agent.ingestion;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.stockquant.server.agent.ingestion.IngestionModels.AssuranceLevel;
import com.stockquant.server.agent.ingestion.IngestionModels.DatasetType;
import com.stockquant.server.agent.ingestion.IngestionModels.PublicationTimeVerification;
import com.stockquant.server.agent.ingestion.IngestionModels.RawRecord;
import com.stockquant.server.agent.ingestion.IngestionModels.RunNamespace;
import com.stockquant.server.agent.temporal.TemporalTrustLevel;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KnowledgeTimePolicyV1Test {

    private final KnowledgeTimePolicyV1 policy = new KnowledgeTimePolicyV1();

    @Test
    void verifiedPublicationDefinesKnowledgeTime() {
        RawRecord raw = raw(TemporalTrustLevel.OBSERVED, PUBLISHED);
        var result = policy.assess(
                raw, TemporalTrustLevel.OBSERVED,
                PublicationTimeVerification.VERIFIED, AssuranceLevel.PIT_VERIFIED);
        assertEquals(PUBLISHED, result.derivedKnownFrom());
        assertEquals(AssuranceLevel.PIT_VERIFIED, result.assuranceLevel());
    }

    @Test
    void unverifiedOrMissingPublicationUsesFirstDurableObservation() {
        var unverified = policy.assess(
                raw(TemporalTrustLevel.OBSERVED, PUBLISHED),
                TemporalTrustLevel.OBSERVED,
                PublicationTimeVerification.UNVERIFIED, AssuranceLevel.PIT_VERIFIED);
        assertEquals(OBSERVED, unverified.derivedKnownFrom());
        assertEquals(AssuranceLevel.RECONSTRUCTED_VERIFIED, unverified.assuranceLevel());

        var missing = policy.assess(
                raw(TemporalTrustLevel.OBSERVED, null),
                TemporalTrustLevel.OBSERVED,
                PublicationTimeVerification.NOT_PROVIDED, AssuranceLevel.PIT_VERIFIED);
        assertEquals(OBSERVED, missing.derivedKnownFrom());
        assertEquals(AssuranceLevel.RECONSTRUCTED_VERIFIED, missing.assuranceLevel());
    }

    @Test
    void inferredSourceCanNeverBePromoted() {
        var result = policy.assess(
                raw(TemporalTrustLevel.BACKFILLED_INFERRED, PUBLISHED),
                TemporalTrustLevel.OBSERVED,
                PublicationTimeVerification.VERIFIED, AssuranceLevel.PIT_VERIFIED);
        assertEquals(AssuranceLevel.INFERRED_RESEARCH, result.assuranceLevel());
    }

    @Test
    void inferredDatasetEnvelopeCanNeverBePromoted() {
        var result = policy.assess(
                raw(TemporalTrustLevel.OBSERVED, PUBLISHED),
                TemporalTrustLevel.BACKFILLED_INFERRED,
                PublicationTimeVerification.VERIFIED, AssuranceLevel.PIT_VERIFIED);
        assertEquals(AssuranceLevel.INFERRED_RESEARCH, result.assuranceLevel());
    }

    @Test
    void backfilledVerifiedTrustCapsAssuranceAtReconstructedEvenWhenPublishedAtIsVerified() {
        assertEquals(AssuranceLevel.RECONSTRUCTED_VERIFIED, policy.assess(
                raw(TemporalTrustLevel.BACKFILLED_VERIFIED, PUBLISHED),
                TemporalTrustLevel.OBSERVED,
                PublicationTimeVerification.VERIFIED,
                AssuranceLevel.PIT_VERIFIED).assuranceLevel());
        assertEquals(AssuranceLevel.RECONSTRUCTED_VERIFIED, policy.assess(
                raw(TemporalTrustLevel.OBSERVED, PUBLISHED),
                TemporalTrustLevel.BACKFILLED_VERIFIED,
                PublicationTimeVerification.VERIFIED,
                AssuranceLevel.PIT_VERIFIED).assuranceLevel());
    }

    @Test
    void publicationVerificationCannotInventOrHideSourceTime() {
        assertThrows(IllegalArgumentException.class, () -> policy.assess(
                raw(TemporalTrustLevel.OBSERVED, null),
                TemporalTrustLevel.OBSERVED,
                PublicationTimeVerification.VERIFIED, AssuranceLevel.PIT_VERIFIED));
        assertThrows(IllegalArgumentException.class, () -> policy.assess(
                raw(TemporalTrustLevel.OBSERVED, PUBLISHED),
                TemporalTrustLevel.OBSERVED,
                PublicationTimeVerification.NOT_PROVIDED, AssuranceLevel.PIT_VERIFIED));
    }

    @Test
    void effectiveDateAndInstantMustAgreeInShanghaiWithoutInventingMidnight() {
        assertThrows(IllegalArgumentException.class, () -> new RawRecord(
                1, DatasetType.SECURITY_STATUS, 1, 1, "raw", RunNamespace.TEST,
                "SOURCE", "V1", "record", "1", "instrument", null, null, PUBLISHED,
                LocalDate.of(2025, 1, 11), Instant.parse("2025-01-09T17:30:00Z"),
                OBSERVED, OBSERVED, TemporalTrustLevel.OBSERVED,
                JsonNodeFactory.instance.objectNode(), "a".repeat(64)));
    }

    private RawRecord raw(TemporalTrustLevel trust, Instant published) {
        return new RawRecord(
                1, DatasetType.SECURITY_STATUS, 1, 1, "raw", RunNamespace.TEST,
                "SOURCE", "V1", "record", "1", "instrument", null, null, published,
                LocalDate.of(2025, 1, 10), Instant.parse("2025-01-09T17:30:00Z"),
                OBSERVED, OBSERVED, trust, JsonNodeFactory.instance.objectNode(),
                "a".repeat(64));
    }

    private static final Instant PUBLISHED = Instant.parse("2025-01-10T00:00:00Z");
    private static final Instant OBSERVED = Instant.parse("2025-01-10T00:01:00Z");
}

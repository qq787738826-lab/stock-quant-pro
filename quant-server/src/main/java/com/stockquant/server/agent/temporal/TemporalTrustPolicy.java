package com.stockquant.server.agent.temporal;

import com.stockquant.server.agent.temporal.TemporalModels.TemporalTrustAssessment;

import java.time.Instant;

/**
 * Conservative provenance assessment for temporal facts.
 *
 * <p>A positive result is only a candidate signal. Stage 2D-2A does not promote
 * any context field to {@code pointInTimeGuaranteed=true}.</p>
 */
public final class TemporalTrustPolicy {

    private TemporalTrustPolicy() {}

    public static TemporalTrustAssessment assess(
            TemporalTrustLevel trustLevel,
            Instant knownAt,
            String source,
            String sourceVersion,
            boolean effectiveTimeDefined
    ) {
        TemporalValidation.required(trustLevel, "trustLevel");
        if (knownAt == null) {
            return new TemporalTrustAssessment(trustLevel, false, "KNOWN_TIME_MISSING");
        }
        if (source == null || source.isBlank() || sourceVersion == null || sourceVersion.isBlank()) {
            return new TemporalTrustAssessment(
                    trustLevel, false, "SOURCE_PROVENANCE_INCOMPLETE");
        }
        if (!effectiveTimeDefined) {
            return new TemporalTrustAssessment(
                    trustLevel, false, "EFFECTIVE_TIME_SEMANTICS_INCOMPLETE");
        }
        return switch (trustLevel) {
            case OBSERVED -> new TemporalTrustAssessment(
                    trustLevel, true, "OBSERVED_POINT_IN_TIME_CANDIDATE");
            case BACKFILLED_VERIFIED -> new TemporalTrustAssessment(
                    trustLevel, true, "BACKFILLED_VERIFIED_POINT_IN_TIME_CANDIDATE");
            case BACKFILLED_INFERRED -> new TemporalTrustAssessment(
                    trustLevel, false, "BACKFILLED_INFERRED_NEVER_POINT_IN_TIME");
        };
    }
}

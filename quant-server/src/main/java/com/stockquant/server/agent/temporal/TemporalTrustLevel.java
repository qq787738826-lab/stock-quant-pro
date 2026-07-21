package com.stockquant.server.agent.temporal;

/**
 * Provenance confidence attached to a temporal fact.
 *
 * <p>This is deliberately not a point-in-time guarantee. Whether a fact is even a
 * candidate for point-in-time use also depends on its knowledge timestamp and
 * source provenance.</p>
 */
public enum TemporalTrustLevel {
    OBSERVED(2),
    BACKFILLED_VERIFIED(1),
    BACKFILLED_INFERRED(0);

    private final int provenanceStrength;

    TemporalTrustLevel(int provenanceStrength) {
        this.provenanceStrength = provenanceStrength;
    }

    /** A derived fact may retain or lower, but never raise, its source trust. */
    public boolean permitsDerived(TemporalTrustLevel derived) {
        TemporalValidation.required(derived, "derived");
        return derived.provenanceStrength <= provenanceStrength;
    }

    /** Returns the most conservative trust level across one provenance chain. */
    public static TemporalTrustLevel mostConservative(TemporalTrustLevel... levels) {
        if (levels == null || levels.length == 0) {
            throw new IllegalArgumentException("at least one trust level is required");
        }
        TemporalTrustLevel result = OBSERVED;
        for (TemporalTrustLevel level : levels) {
            TemporalValidation.required(level, "trustLevel");
            if (level.provenanceStrength < result.provenanceStrength) {
                result = level;
            }
        }
        return result;
    }
}

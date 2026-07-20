package com.stockquant.server.agent.temporal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.temporal.TemporalModels.AppendSecurityStatusEventCommand;
import com.stockquant.server.agent.temporal.TemporalModels.AppendTradingCalendarRevisionCommand;
import com.stockquant.server.agent.temporal.TemporalModels.PublishSecurityStatusVersionCommand;
import com.stockquant.server.agent.temporal.TemporalModels.RegisterDatasetVersionCommand;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemporalModelsTest {

    private static final String HASH = "a".repeat(64);
    private static final Instant KNOWN_FROM = Instant.parse("2025-01-02T08:00:00Z");
    private static final LocalDate VALID_FROM = LocalDate.of(2025, 1, 2);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void appliesConservativePointInTimeCandidatePolicy() {
        assertTrue(TemporalTrustPolicy.assess(
                TemporalTrustLevel.OBSERVED, KNOWN_FROM, "TEST", "v1", true
        ).pointInTimeCandidate());
        assertTrue(TemporalTrustPolicy.assess(
                TemporalTrustLevel.BACKFILLED_VERIFIED, KNOWN_FROM, "TEST", "v1", true
        ).pointInTimeCandidate());
        assertFalse(TemporalTrustPolicy.assess(
                TemporalTrustLevel.BACKFILLED_INFERRED, KNOWN_FROM, "TEST", "v1", true
        ).pointInTimeCandidate());
        assertFalse(TemporalTrustPolicy.assess(
                TemporalTrustLevel.OBSERVED, null, "TEST", "v1", true
        ).pointInTimeCandidate());
        assertFalse(TemporalTrustPolicy.assess(
                TemporalTrustLevel.OBSERVED, KNOWN_FROM, " ", "v1", true
        ).pointInTimeCandidate());
        assertFalse(TemporalTrustPolicy.assess(
                TemporalTrustLevel.OBSERVED, KNOWN_FROM, "TEST", "v1", false
        ).pointInTimeCandidate());
        assertTrue(TemporalTrustLevel.OBSERVED.permitsDerived(
                TemporalTrustLevel.BACKFILLED_VERIFIED));
        assertFalse(TemporalTrustLevel.BACKFILLED_INFERRED.permitsDerived(
                TemporalTrustLevel.OBSERVED));
        assertEquals(TemporalTrustLevel.BACKFILLED_INFERRED,
                TemporalTrustLevel.mostConservative(
                        TemporalTrustLevel.OBSERVED,
                        TemporalTrustLevel.BACKFILLED_INFERRED,
                        TemporalTrustLevel.BACKFILLED_VERIFIED));
    }

    @Test
    void rejectsInvalidDatasetAndSecurityIntervals() {
        assertThrows(IllegalArgumentException.class, () -> new RegisterDatasetVersionCommand(
                "SECURITY_STATUS", "TEST", "v1", "connector-v1",
                VALID_FROM, VALID_FROM.minusDays(1), KNOWN_FROM, HASH,
                TemporalTrustLevel.OBSERVED, MAPPER.createObjectNode()
        ));
        assertThrows(IllegalArgumentException.class, () -> securityVersion(
                VALID_FROM, VALID_FROM, KNOWN_FROM, null
        ));
        assertThrows(IllegalArgumentException.class, () -> securityVersion(
                VALID_FROM, null, KNOWN_FROM, KNOWN_FROM
        ));
    }

    @Test
    void rejectsBlankIdentityAndSourceFields() {
        assertThrows(IllegalArgumentException.class, () -> new AppendSecurityStatusEventCommand(
                1, " ", SecurityStatusEventType.LISTING, VALID_FROM, null,
                null, KNOWN_FROM, "TEST", "v1", "record-1", "1",
                TemporalTrustLevel.OBSERVED, MAPPER.createObjectNode(), HASH, null
        ));
        assertThrows(IllegalArgumentException.class, () -> new AppendSecurityStatusEventCommand(
                1, "600000", SecurityStatusEventType.LISTING, VALID_FROM, null,
                KNOWN_FROM.plusSeconds(1), KNOWN_FROM, "TEST", "v1", "record-1", "1",
                TemporalTrustLevel.OBSERVED, MAPPER.createObjectNode(), HASH, null
        ));
        assertThrows(IllegalArgumentException.class, () -> new AppendSecurityStatusEventCommand(
                1, "600000", SecurityStatusEventType.LISTING, VALID_FROM, null,
                null, KNOWN_FROM, " ", "v1", "record-1", "1",
                TemporalTrustLevel.OBSERVED, MAPPER.createObjectNode(), HASH, null
        ));
        assertThrows(IllegalArgumentException.class, () -> new AppendSecurityStatusEventCommand(
                1, "600000", SecurityStatusEventType.LISTING, VALID_FROM, null,
                null, KNOWN_FROM, "TEST", " ", "record-1", "1",
                TemporalTrustLevel.OBSERVED, MAPPER.createObjectNode(), HASH, null
        ));
    }

    @Test
    void enforcesOpenAndClosedSessionSemantics() {
        Instant opensAt = Instant.parse("2025-01-02T01:30:00Z");
        Instant closesAt = Instant.parse("2025-01-02T07:00:00Z");
        new AppendTradingCalendarRevisionCommand(
                1, MarketExchange.SSE, VALID_FROM, true, TradingSessionType.REGULAR,
                opensAt, closesAt, VALID_FROM.minusDays(1), VALID_FROM.plusDays(1),
                KNOWN_FROM, null, "TEST", "v1", "calendar-1", "1",
                TemporalTrustLevel.OBSERVED, HASH
        );
        new AppendTradingCalendarRevisionCommand(
                1, MarketExchange.SZSE, VALID_FROM, false, TradingSessionType.HOLIDAY,
                null, null, VALID_FROM.minusDays(1), VALID_FROM.plusDays(1),
                KNOWN_FROM, null, "TEST", "v1", "calendar-2", "1",
                TemporalTrustLevel.OBSERVED, HASH
        );

        assertThrows(IllegalArgumentException.class, () -> new AppendTradingCalendarRevisionCommand(
                1, MarketExchange.SSE, VALID_FROM, true, TradingSessionType.HOLIDAY,
                opensAt, closesAt, null, null, KNOWN_FROM, null,
                "TEST", "v1", "calendar-3", "1", TemporalTrustLevel.OBSERVED, HASH
        ));
        assertThrows(IllegalArgumentException.class, () -> new AppendTradingCalendarRevisionCommand(
                1, MarketExchange.SSE, VALID_FROM, false, TradingSessionType.TEMPORARY_CLOSURE,
                opensAt, closesAt, null, null, KNOWN_FROM, null,
                "TEST", "v1", "calendar-4", "1", TemporalTrustLevel.OBSERVED, HASH
        ));
        assertThrows(IllegalArgumentException.class, () -> new AppendTradingCalendarRevisionCommand(
                1, MarketExchange.SSE, VALID_FROM, true, TradingSessionType.REGULAR,
                closesAt, opensAt, null, null, KNOWN_FROM, null,
                "TEST", "v1", "calendar-5", "1", TemporalTrustLevel.OBSERVED, HASH
        ));
    }

    @Test
    void producesDeterministicStatusHashWithoutUsingKnowledgeTime() {
        SecurityStatusStateHasher hasher = new SecurityStatusStateHasher();
        PublishSecurityStatusVersionCommand first = securityVersion(
                VALID_FROM, null, KNOWN_FROM, null
        );
        PublishSecurityStatusVersionCommand retry = securityVersion(
                VALID_FROM, null, KNOWN_FROM.plusSeconds(60), null
        );
        PublishSecurityStatusVersionCommand changed = new PublishSecurityStatusVersionCommand(
                "600000", MarketExchange.SSE, "MAIN", true, true, true,
                VALID_FROM, null, KNOWN_FROM, null, 1, 1,
                "TEST", "v1", TemporalTrustLevel.OBSERVED
        );

        assertEquals(hasher.hash(first), hasher.hash(first));
        assertEquals(hasher.hash(first), hasher.hash(retry));
        assertNotEquals(hasher.hash(first), hasher.hash(changed));
        assertEquals(64, hasher.hash(first).length());
    }

    @Test
    void constructsIdempotencyKeysFromTheExactFrozenTuples() {
        RegisterDatasetVersionCommand dataset = new RegisterDatasetVersionCommand(
                "SECURITY_STATUS", "TEST", "v1", "connector-v1",
                VALID_FROM, VALID_FROM.plusDays(1), KNOWN_FROM, HASH,
                TemporalTrustLevel.OBSERVED, MAPPER.createObjectNode()
        );
        assertEquals(
                TemporalIdempotencyKeys.dataset(dataset),
                TemporalIdempotencyKeys.dataset(dataset)
        );

        AppendSecurityStatusEventCommand event = new AppendSecurityStatusEventCommand(
                1, "600000", SecurityStatusEventType.LISTING, VALID_FROM, null,
                null, KNOWN_FROM, "TEST", "v1", "record-1", "1",
                TemporalTrustLevel.OBSERVED, MAPPER.createObjectNode(), HASH, null
        );
        assertEquals(
                TemporalIdempotencyKeys.securityEvent(event),
                TemporalIdempotencyKeys.securityEvent(event)
        );
        assertNotEquals(
                TemporalIdempotencyKeys.securityEvent(event),
                TemporalIdempotencyKeys.securityEvent(new AppendSecurityStatusEventCommand(
                        1, "600000", SecurityStatusEventType.LISTING, VALID_FROM, null,
                        null, KNOWN_FROM, "TEST", "v1", "record-1", "2",
                        TemporalTrustLevel.OBSERVED, MAPPER.createObjectNode(), HASH, null
                ))
        );
    }

    @Test
    void comparesJsonbContentByMathematicalNumericValueAndArrayOrder() throws Exception {
        assertTrue(TemporalJsonSemantics.same(
                MAPPER.readTree("{\"amount\":10.0000,\"nested\":[1,2]}"),
                MAPPER.readTree("{\"nested\":[1.0,2.00],\"amount\":1E+1}")));
        assertFalse(TemporalJsonSemantics.same(
                MAPPER.readTree("{\"nested\":[1,2]}"),
                MAPPER.readTree("{\"nested\":[2,1]}")));
        assertFalse(TemporalJsonSemantics.same(
                MAPPER.readTree("{\"value\":1}"),
                MAPPER.readTree("{\"value\":\"1\"}")));
    }

    private static PublishSecurityStatusVersionCommand securityVersion(
            LocalDate validFrom,
            LocalDate validTo,
            Instant knownFrom,
            Instant knownTo
    ) {
        return new PublishSecurityStatusVersionCommand(
                "600000", MarketExchange.SSE, "MAIN", true, true, false,
                validFrom, validTo, knownFrom, knownTo, 1, 1,
                "TEST", "v1", TemporalTrustLevel.OBSERVED
        );
    }
}

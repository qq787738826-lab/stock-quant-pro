package com.stockquant.server.agent.temporal;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.temporal.SecurityStatusEventPayloadContract.SecurityStatusState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecurityStatusEventPayloadContractTest {

    private static final SecurityStatusState BASE =
            new SecurityStatusState(MarketExchange.SSE, "MAIN", true, true, false);

    @Test
    void roundTripsAndHashesCanonicalVersionedPayloadDeterministically() {
        ObjectNode payload = SecurityStatusEventPayloadContract.payload(BASE);
        assertEquals(BASE, SecurityStatusEventPayloadContract.parse(payload));
        assertEquals(SecurityStatusEventPayloadContract.hash(payload),
                SecurityStatusEventPayloadContract.hash(payload.deepCopy()));
        assertNotEquals(SecurityStatusEventPayloadContract.hash(payload),
                SecurityStatusEventPayloadContract.hash(SecurityStatusEventPayloadContract.payload(
                        new SecurityStatusState(MarketExchange.SSE, "MAIN", true, true, true))));
    }

    @Test
    void validatesEveryFrozenTransitionAndRejectsNonTargetChanges() {
        SecurityStatusEventPayloadContract.validateTransition(
                SecurityStatusEventType.FULL_STATUS_SNAPSHOT, null, BASE);
        SecurityStatusEventPayloadContract.validateTransition(
                SecurityStatusEventType.ST_CHANGE, BASE,
                new SecurityStatusState(MarketExchange.SSE, "MAIN", true, true, true));
        SecurityStatusEventPayloadContract.validateTransition(
                SecurityStatusEventType.BOARD_CHANGE, BASE,
                new SecurityStatusState(MarketExchange.SSE, "SECONDARY", true, true, false));
        SecurityStatusEventPayloadContract.validateTransition(
                SecurityStatusEventType.ACTIVE_CHANGE, BASE,
                new SecurityStatusState(MarketExchange.SSE, "MAIN", true, false, false));
        SecurityStatusEventPayloadContract.validateTransition(
                SecurityStatusEventType.EXCHANGE_CHANGE, BASE,
                new SecurityStatusState(MarketExchange.SZSE, "MAIN", true, true, false));
        var unlisted = new SecurityStatusState(MarketExchange.SSE, "MAIN", false, false, false);
        SecurityStatusEventPayloadContract.validateTransition(
                SecurityStatusEventType.LISTING, unlisted, BASE);
        SecurityStatusEventPayloadContract.validateTransition(
                SecurityStatusEventType.DELISTING, BASE, unlisted);

        assertThrows(IllegalArgumentException.class, () ->
                SecurityStatusEventPayloadContract.validateTransition(
                        SecurityStatusEventType.ST_CHANGE, BASE,
                        new SecurityStatusState(MarketExchange.SZSE, "MAIN", true, true, true)));
        assertThrows(IllegalArgumentException.class, () ->
                SecurityStatusEventPayloadContract.validateTransition(
                        SecurityStatusEventType.BOARD_CHANGE, BASE, BASE));
        assertThrows(IllegalArgumentException.class, () ->
                SecurityStatusEventPayloadContract.validateTransition(
                        SecurityStatusEventType.ACTIVE_CHANGE, null, BASE));
    }

    @Test
    void rejectsMissingWrongTypedAndExtraPayloadFields() {
        ObjectNode missing = SecurityStatusEventPayloadContract.payload(BASE);
        missing.path("resultingState").deepCopy();
        ((ObjectNode) missing.get("resultingState")).remove("isSt");
        assertThrows(IllegalArgumentException.class,
                () -> SecurityStatusEventPayloadContract.parse(missing));

        ObjectNode wrongType = SecurityStatusEventPayloadContract.payload(BASE);
        ((ObjectNode) wrongType.get("resultingState")).put("listed", "true");
        assertThrows(IllegalArgumentException.class,
                () -> SecurityStatusEventPayloadContract.parse(wrongType));

        ObjectNode extra = SecurityStatusEventPayloadContract.payload(BASE);
        extra.put("recommendation", "BUY");
        assertThrows(IllegalArgumentException.class,
                () -> SecurityStatusEventPayloadContract.parse(extra));
    }
}

package com.stockquant.server.agent.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels.ParsedSecurityStatusRaw;
import com.stockquant.server.agent.temporal.MarketExchange;
import com.stockquant.server.agent.temporal.SecurityStatusEventPayloadContract.SecurityStatusState;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/** Strict, network-free parser for the frozen TEST/DEMO raw contract. */
@Component
public class SecurityStatusRawTestV1Parser {

    private static final Set<String> ROOT_FIELDS = Set.of("schemaVersion", "symbol", "state");
    private static final Set<String> STATE_FIELDS = Set.of(
            "exchange", "board", "listed", "active", "isSt");

    public ParsedSecurityStatusRaw parse(JsonNode payload) {
        JsonNode root = IngestionValidation.object(payload, "rawPayload");
        requireExactFields(root, ROOT_FIELDS, "rawPayload");
        JsonNode version = root.get("schemaVersion");
        if (!version.isTextual()
                || !SecurityEventMaterializationModels.RAW_CONTRACT_VERSION.equals(
                        version.textValue())) {
            throw new UnsupportedRawContractException(
                    "raw schemaVersion must be "
                            + SecurityEventMaterializationModels.RAW_CONTRACT_VERSION);
        }
        JsonNode symbol = root.get("symbol");
        JsonNode state = root.get("state");
        if (!symbol.isTextual() || state == null || !state.isObject()) {
            throw new IllegalArgumentException("raw symbol/state has an invalid type");
        }
        requireExactFields(state, STATE_FIELDS, "rawPayload.state");
        JsonNode exchange = state.get("exchange");
        JsonNode board = state.get("board");
        JsonNode listed = state.get("listed");
        JsonNode active = state.get("active");
        JsonNode st = state.get("isSt");
        if (!exchange.isTextual() || !board.isTextual()
                || !listed.isBoolean() || !active.isBoolean() || !st.isBoolean()) {
            throw new IllegalArgumentException("raw state contains invalid field types");
        }
        SecurityStatusState resultingState;
        try {
            resultingState = new SecurityStatusState(
                    MarketExchange.valueOf(exchange.textValue()),
                    IngestionValidation.text(board.textValue(), "state.board", 64),
                    listed.booleanValue(), active.booleanValue(), st.booleanValue());
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("raw state contains invalid values", error);
        }
        SecurityEventMaterializationModels.requireStateInvariant(resultingState);
        return new ParsedSecurityStatusRaw(
                IngestionValidation.text(symbol.textValue(), "symbol", 12), resultingState);
    }

    public String schemaVersion(JsonNode payload) {
        JsonNode root = IngestionValidation.object(payload, "rawPayload");
        JsonNode value = root.get("schemaVersion");
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static void requireExactFields(JsonNode value, Set<String> expected, String name) {
        Set<String> actual = new HashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(name + " fields must exactly match " + expected);
        }
    }

    public static final class UnsupportedRawContractException extends IllegalArgumentException {
        public UnsupportedRawContractException(String message) {
            super(message);
        }
    }
}

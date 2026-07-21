package com.stockquant.server.agent.temporal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/** Versioned, deterministic payload contract for authoritative security status events. */
public final class SecurityStatusEventPayloadContract {

    public static final String VERSION = "SECURITY_STATUS_EVENT_V1";

    private static final Set<String> ROOT_FIELDS = Set.of("schemaVersion", "resultingState");
    private static final Set<String> STATE_FIELDS = Set.of(
            "exchange", "board", "listed", "active", "isSt");

    private SecurityStatusEventPayloadContract() {}

    public static ObjectNode payload(SecurityStatusState state) {
        TemporalValidation.required(state, "state");
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("schemaVersion", VERSION);
        ObjectNode result = root.putObject("resultingState");
        result.put("exchange", state.exchange().name());
        result.put("board", state.board());
        result.put("listed", state.listed());
        result.put("active", state.active());
        result.put("isSt", state.st());
        return root;
    }

    public static SecurityStatusState parse(JsonNode payload) {
        JsonNode root = TemporalValidation.object(payload, "payload");
        requireExactFields(root, ROOT_FIELDS, "payload");
        JsonNode version = root.get("schemaVersion");
        if (!version.isTextual() || !VERSION.equals(version.textValue())) {
            throw new IllegalArgumentException("payload schemaVersion must be " + VERSION);
        }
        JsonNode state = root.get("resultingState");
        if (state == null || !state.isObject()) {
            throw new IllegalArgumentException("payload resultingState must be an object");
        }
        requireExactFields(state, STATE_FIELDS, "payload resultingState");
        JsonNode exchange = state.get("exchange");
        JsonNode board = state.get("board");
        JsonNode listed = state.get("listed");
        JsonNode active = state.get("active");
        JsonNode st = state.get("isSt");
        if (!exchange.isTextual() || !board.isTextual()
                || !listed.isBoolean() || !active.isBoolean() || !st.isBoolean()) {
            throw new IllegalArgumentException("payload resultingState contains invalid field types");
        }
        try {
            return new SecurityStatusState(
                    MarketExchange.valueOf(exchange.textValue()), board.textValue(),
                    listed.booleanValue(), active.booleanValue(), st.booleanValue());
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("payload resultingState contains invalid values", error);
        }
    }

    public static String hash(JsonNode payload) {
        SecurityStatusState state = parse(payload);
        byte[] canonical = payload(state).toString().getBytes(StandardCharsets.UTF_8);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    public static void validateTransition(
            SecurityStatusEventType type,
            SecurityStatusState previous,
            SecurityStatusState resulting
    ) {
        TemporalValidation.required(type, "eventType");
        TemporalValidation.required(resulting, "resultingState");
        if (type == SecurityStatusEventType.FULL_STATUS_SNAPSHOT) {
            if (previous != null) {
                throw new IllegalArgumentException("FULL_STATUS_SNAPSHOT must establish an initial state");
            }
            return;
        }
        if (previous == null) {
            throw new IllegalArgumentException(type + " requires a superseded state");
        }
        switch (type) {
            case ST_CHANGE -> requireOnlyTargetChanged(previous, resulting, "isSt",
                    previous.exchange() == resulting.exchange()
                            && previous.board().equals(resulting.board())
                            && previous.listed() == resulting.listed()
                            && previous.active() == resulting.active()
                            && previous.st() != resulting.st());
            case BOARD_CHANGE -> requireOnlyTargetChanged(previous, resulting, "board",
                    previous.exchange() == resulting.exchange()
                            && !previous.board().equals(resulting.board())
                            && previous.listed() == resulting.listed()
                            && previous.active() == resulting.active()
                            && previous.st() == resulting.st());
            case ACTIVE_CHANGE -> requireOnlyTargetChanged(previous, resulting, "active",
                    previous.exchange() == resulting.exchange()
                            && previous.board().equals(resulting.board())
                            && previous.listed() == resulting.listed()
                            && previous.active() != resulting.active()
                            && previous.st() == resulting.st());
            case EXCHANGE_CHANGE -> requireOnlyTargetChanged(previous, resulting, "exchange",
                    previous.exchange() != resulting.exchange()
                            && previous.board().equals(resulting.board())
                            && previous.listed() == resulting.listed()
                            && previous.active() == resulting.active()
                            && previous.st() == resulting.st());
            case LISTING -> requireOnlyTargetChanged(previous, resulting, "listing",
                    previous.exchange() == resulting.exchange()
                            && previous.board().equals(resulting.board())
                            && !previous.listed() && !previous.active()
                            && resulting.listed() && resulting.active()
                            && previous.st() == resulting.st());
            case DELISTING -> requireOnlyTargetChanged(previous, resulting, "delisting",
                    previous.exchange() == resulting.exchange()
                            && previous.board().equals(resulting.board())
                            && previous.listed()
                            && !resulting.listed() && !resulting.active()
                            && previous.st() == resulting.st());
            default -> throw new IllegalArgumentException("unsupported event transition: " + type);
        }
    }

    private static void requireOnlyTargetChanged(
            SecurityStatusState previous,
            SecurityStatusState resulting,
            String target,
            boolean valid
    ) {
        if (!valid) {
            throw new IllegalArgumentException(
                    "event transition may only apply its frozen " + target + " change");
        }
    }

    private static void requireExactFields(JsonNode node, Set<String> expected, String name) {
        Set<String> actual = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(name + " fields must exactly match " + expected);
        }
    }

    public record SecurityStatusState(
            MarketExchange exchange,
            String board,
            boolean listed,
            boolean active,
            boolean st
    ) {
        public SecurityStatusState {
            exchange = TemporalValidation.required(exchange, "exchange");
            board = TemporalValidation.requiredText(board, "board");
        }
    }
}

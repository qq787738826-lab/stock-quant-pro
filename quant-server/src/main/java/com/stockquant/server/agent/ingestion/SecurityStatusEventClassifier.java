package com.stockquant.server.agent.ingestion;

import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels.MaterializedSecurityEvent;
import com.stockquant.server.agent.ingestion.SecurityEventMaterializationModels.ParsedSecurityStatusRaw;
import com.stockquant.server.agent.temporal.SecurityStatusEventPayloadContract;
import com.stockquant.server.agent.temporal.SecurityStatusEventPayloadContract.SecurityStatusState;
import com.stockquant.server.agent.temporal.SecurityStatusEventType;

import org.springframework.stereotype.Component;

/** Deterministic classifier that preserves the frozen SECURITY_STATUS_EVENT_V1 transitions. */
@Component
public class SecurityStatusEventClassifier {

    public Classification classify(
            ParsedSecurityStatusRaw raw,
            MaterializedSecurityEvent predecessor
    ) {
        IngestionValidation.required(raw, "raw");
        SecurityStatusState next = raw.state();
        if (predecessor == null) {
            SecurityStatusEventPayloadContract.validateTransition(
                    SecurityStatusEventType.FULL_STATUS_SNAPSHOT, null, next);
            return Classification.event(SecurityStatusEventType.FULL_STATUS_SNAPSHOT);
        }
        if (!predecessor.symbol().equals(raw.symbol())) {
            return Classification.unsupported("SYMBOL_CHANGE_REQUIRES_IDENTITY_GOVERNANCE");
        }
        SecurityStatusState previous = SecurityStatusEventPayloadContract.parse(
                predecessor.payload());
        if (previous.equals(next)) return Classification.unchanged();

        SecurityStatusEventType type = classifyChangedState(previous, next);
        if (type == null) {
            return Classification.unsupported("MULTI_FIELD_OR_V2_CORRECTION_REQUIRED");
        }
        try {
            SecurityStatusEventPayloadContract.validateTransition(type, previous, next);
            return Classification.event(type);
        } catch (IllegalArgumentException error) {
            return Classification.unsupported("INVALID_V1_STATE_TRANSITION");
        }
    }

    private static SecurityStatusEventType classifyChangedState(
            SecurityStatusState previous,
            SecurityStatusState next
    ) {
        boolean exchange = previous.exchange() != next.exchange();
        boolean board = !previous.board().equals(next.board());
        boolean listed = previous.listed() != next.listed();
        boolean active = previous.active() != next.active();
        boolean st = previous.st() != next.st();
        int changed = bool(exchange) + bool(board) + bool(listed) + bool(active) + bool(st);
        if (changed == 1 && st) return SecurityStatusEventType.ST_CHANGE;
        if (changed == 1 && board) return SecurityStatusEventType.BOARD_CHANGE;
        if (changed == 1 && active) return SecurityStatusEventType.ACTIVE_CHANGE;
        if (changed == 1 && exchange) return SecurityStatusEventType.EXCHANGE_CHANGE;
        if (changed == 2 && listed && active
                && !previous.listed() && !previous.active()
                && next.listed() && next.active()) {
            return SecurityStatusEventType.LISTING;
        }
        if ((changed == 1 || changed == 2) && listed
                && previous.listed() && !next.listed() && !next.active()
                && (!active || previous.active())) {
            return SecurityStatusEventType.DELISTING;
        }
        return null;
    }

    private static int bool(boolean value) {
        return value ? 1 : 0;
    }

    public record Classification(
            SecurityStatusEventType eventType,
            boolean noStateChange,
            String errorCode
    ) {
        static Classification event(SecurityStatusEventType type) {
            return new Classification(type, false, null);
        }

        static Classification unchanged() {
            return new Classification(null, true, null);
        }

        static Classification unsupported(String errorCode) {
            return new Classification(null, false, errorCode);
        }

        public boolean unsupported() {
            return errorCode != null;
        }
    }
}

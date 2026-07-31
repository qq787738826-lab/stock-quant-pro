package com.stockquant.server.agent.marketfacts;

import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Exact one-day, one-to-three symbol F1E manual batch command.
 */
public record TushareDedicatedResearchBatchCommand(
        LocalDate tradeDate,
        List<SecuritySelection> securities,
        Duration timeout
) {

    public static final int MAXIMUM_SYMBOLS = 3;
    public static final int REQUESTS_PER_SYMBOL = 3;
    public static final int MAXIMUM_PROVIDER_REQUESTS = 9;

    public TushareDedicatedResearchBatchCommand {
        tradeDate = Objects.requireNonNull(tradeDate, "tradeDate");
        securities = List.copyOf(Objects.requireNonNull(
                securities, "securities"));
        timeout = Objects.requireNonNull(timeout, "timeout");
        if (securities.isEmpty()
                || securities.size() > MAXIMUM_SYMBOLS
                || timeout.isZero()
                || timeout.isNegative()) {
            throw invalid();
        }
        Set<String> identities = new LinkedHashSet<>();
        for (SecuritySelection security : securities) {
            Objects.requireNonNull(security, "security");
            if (!identities.add(security.providerInstrumentId())) {
                throw invalid();
            }
        }
        if (securities.size() * REQUESTS_PER_SYMBOL
                > MAXIMUM_PROVIDER_REQUESTS) {
            throw invalid();
        }
    }

    public int expectedProviderRequests() {
        return securities.size() * REQUESTS_PER_SYMBOL;
    }

    public record SecuritySelection(
            String symbol,
            String exchange
    ) {
        public SecuritySelection {
            if (symbol == null || !symbol.matches("[0-9]{6}")
                    || !isMainBoard(symbol, exchange)) {
                throw invalid();
            }
        }

        public String providerInstrumentId() {
            return symbol + ("SSE".equals(exchange) ? ".SH" : ".SZ");
        }
    }

    private static boolean isMainBoard(String symbol, String exchange) {
        return switch (exchange) {
            case "SSE" -> symbol.matches("60[0135][0-9]{3}");
            case "SZSE" -> symbol.matches("00[0123][0-9]{3}");
            default -> false;
        };
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "TUSHARE_DEDICATED_RESEARCH_COMMAND_INVALID");
    }
}

package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchCommand.SecuritySelection;

import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Bounded, manual M1 capture command for a historical research window. */
public record TushareM1ResearchWindowCommand(
        List<SecuritySelection> securities,
        LocalDate rangeStart,
        LocalDate rangeEnd,
        LocalDate anchorTradeDate,
        Mode mode,
        Duration timeout
) {
    public TushareM1ResearchWindowCommand {
        securities = List.copyOf(Objects.requireNonNull(
                securities, "securities"));
        rangeStart = Objects.requireNonNull(rangeStart, "rangeStart");
        rangeEnd = Objects.requireNonNull(rangeEnd, "rangeEnd");
        anchorTradeDate = Objects.requireNonNull(
                anchorTradeDate, "anchorTradeDate");
        mode = Objects.requireNonNull(mode, "mode");
        timeout = Objects.requireNonNull(timeout, "timeout");
        if (securities.isEmpty()
                || securities.size()
                > TushareManualBoundedSession.M1_MAX_SYMBOLS
                || rangeEnd.isBefore(rangeStart)
                || anchorTradeDate.isBefore(rangeStart)
                || anchorTradeDate.isAfter(rangeEnd)
                || ChronoUnit.DAYS.between(rangeStart, rangeEnd) + 1
                > TushareManualBoundedSession.M1_MAX_NATURAL_DAYS
                || timeout.isZero() || timeout.isNegative()) {
            throw invalid();
        }
        Set<String> identities = new LinkedHashSet<>();
        for (SecuritySelection security : securities) {
            Objects.requireNonNull(security, "security");
            if (!identities.add(security.providerInstrumentId())) {
                throw invalid();
            }
        }
    }

    public int expectedProviderRequests() {
        return securities.size() * 3;
    }

    public Set<String> providerInstrumentIds() {
        return securities.stream()
                .map(SecuritySelection::providerInstrumentId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public Set<String> exchanges() {
        return securities.stream()
                .map(SecuritySelection::exchange)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "TUSHARE_M1_RESEARCH_WINDOW_COMMAND_INVALID");
    }

    public enum Mode {
        CAPTURE,
        IDEMPOTENCY_VERIFICATION,
        /** Continuous research accepts either a new append or a legal tail hit. */
        CAPTURE_OR_IDEMPOTENT
    }
}

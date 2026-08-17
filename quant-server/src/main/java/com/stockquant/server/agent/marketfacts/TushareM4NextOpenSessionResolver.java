package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.PitMarketFactModels.TradingCalendarObservation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Resolves a paper execution date only from complete PIT calendar evidence. */
final class TushareM4NextOpenSessionResolver {
    private final PitMarketFactRepository repository;

    TushareM4NextOpenSessionResolver(PitMarketFactRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    Optional<LocalDate> resolve(
            LocalDate signalDate,
            LocalDate horizonEnd,
            Instant knowledgeCutoff
    ) {
        return resolve(signalDate, horizonEnd, knowledgeCutoff, null);
    }

    Optional<LocalDate> resolveAfterResearchAsOf(
            LocalDate signalDate,
            LocalDate horizonEnd,
            Instant knowledgeCutoff
    ) {
        return resolve(signalDate, horizonEnd, knowledgeCutoff,
                knowledgeCutoff);
    }

    private Optional<LocalDate> resolve(
            LocalDate signalDate,
            LocalDate horizonEnd,
            Instant knowledgeCutoff,
            Instant executionAfter
    ) {
        Objects.requireNonNull(signalDate, "signalDate");
        Objects.requireNonNull(horizonEnd, "horizonEnd");
        Objects.requireNonNull(knowledgeCutoff, "knowledgeCutoff");
        LocalDate from = signalDate.plusDays(1);
        if (horizonEnd.isBefore(from)
                || horizonEnd.isAfter(signalDate.plusDays(30))) {
            throw new IllegalArgumentException(
                    "M4_NEXT_OPEN_RESOLUTION_SCOPE_INVALID");
        }
        Map<LocalDate, Boolean> sse = completeCalendar("SSE", from,
                horizonEnd, knowledgeCutoff);
        Map<LocalDate, Boolean> szse = completeCalendar("SZSE", from,
                horizonEnd, knowledgeCutoff);
        if (sse.isEmpty() || szse.isEmpty()) {
            return Optional.empty();
        }
        for (LocalDate date = from; !date.isAfter(horizonEnd);
                date = date.plusDays(1)) {
            if (!sse.containsKey(date) || !szse.containsKey(date)) {
                return Optional.empty();
            }
            if (Boolean.TRUE.equals(sse.get(date))
                    && Boolean.TRUE.equals(szse.get(date))
                    && (executionAfter == null
                    || com.stockquant.core.research.StrategyResearchModels
                    .openInstant(date).isAfter(executionAfter))) {
                return Optional.of(date);
            }
        }
        return Optional.empty();
    }

    private Map<LocalDate, Boolean> completeCalendar(
            String exchange,
            LocalDate from,
            LocalDate to,
            Instant cutoff
    ) {
        List<TradingCalendarObservation> values = repository.findCalendarAsOf(
                TushareMarketFactProvider.PROVIDER_CODE,
                TushareMarketFactProvider.calendarSourceIdentity(exchange),
                exchange, from, to, cutoff);
        Map<LocalDate, Boolean> result = new LinkedHashMap<>();
        for (TradingCalendarObservation value : values) {
            if (!exchange.equals(value.exchange())
                    || value.calendarDate().isBefore(from)
                    || value.calendarDate().isAfter(to)
                    || value.envelope().knownAt().isAfter(cutoff)
                    || result.put(value.calendarDate(), value.open())
                    != null) {
                return Map.of();
            }
        }
        return Map.copyOf(result);
    }
}

package com.stockquant.server.agent.temporal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.temporal.TemporalModels.AppendSecurityStatusEventCommand;
import com.stockquant.server.agent.temporal.TemporalModels.AppendTradingCalendarRevisionCommand;
import com.stockquant.server.agent.temporal.TemporalModels.CorrectSecurityStatusVersionCommand;
import com.stockquant.server.agent.temporal.TemporalModels.CorrectTradingCalendarRevisionCommand;
import com.stockquant.server.agent.temporal.TemporalModels.DatasetVersion;
import com.stockquant.server.agent.temporal.TemporalModels.PublishSecurityStatusVersionCommand;
import com.stockquant.server.agent.temporal.TemporalModels.SecurityStatusEvent;
import com.stockquant.server.agent.temporal.TemporalModels.SecurityStatusVersion;
import com.stockquant.server.agent.temporal.TemporalModels.TradingCalendarRevision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemporalMarketFoundationServiceTest {

    private static final String HASH = "a".repeat(64);
    private static final String SOURCE = "TEST";
    private static final String VERSION = "v1";
    private static final LocalDate VALID_FROM = LocalDate.of(2025, 1, 1);
    private static final Instant KNOWN_1 = Instant.parse("2025-01-02T00:00:00Z");
    private static final Instant KNOWN_2 = Instant.parse("2025-02-02T00:00:00Z");
    private static final Instant RECORDED = Instant.parse("2026-01-01T00:00:00Z");

    private MarketDataDatasetVersionRepository datasets;
    private SecurityStatusEventRepository events;
    private SecurityStatusHistoryRepository history;
    private TradingCalendarRevisionRepository calendars;
    private SecurityStatusStateHasher hasher;
    private TemporalMarketFoundationService service;

    @BeforeEach
    void setUp() {
        datasets = mock(MarketDataDatasetVersionRepository.class);
        events = mock(SecurityStatusEventRepository.class);
        history = mock(SecurityStatusHistoryRepository.class);
        calendars = mock(TradingCalendarRevisionRepository.class);
        hasher = mock(SecurityStatusStateHasher.class);
        service = new TemporalMarketFoundationService(
                datasets, events, history, calendars, hasher,
                Clock.fixed(RECORDED, ZoneOffset.UTC));
    }

    @Test
    void rejectsTrustElevationFromDatasetBeforeWritingEvent() {
        when(datasets.findById(1)).thenReturn(Optional.of(dataset(
                1, TemporalTrustLevel.BACKFILLED_INFERRED)));
        AppendSecurityStatusEventCommand command = eventCommand(
                10, null, KNOWN_1, TemporalTrustLevel.OBSERVED);

        assertThrows(TemporalDataConflictException.class,
                () -> service.appendSecurityStatusEvent(command));
        verify(events, never()).insertIfAbsent(command, RECORDED);
    }

    @Test
    void returnsSameSecurityCorrectionAfterAnotherCallerClosedAndInserted() {
        DatasetVersion dataset = dataset(1, TemporalTrustLevel.OBSERVED);
        SecurityStatusEvent replacementEvent = event(
                20, 10L, KNOWN_2, TemporalTrustLevel.OBSERVED);
        SecurityStatusVersion superseded = statusVersion(
                30, 10, KNOWN_1, KNOWN_2, false, TemporalTrustLevel.OBSERVED);
        PublishSecurityStatusVersionCommand replacement = statusCommand(
                20, KNOWN_2, true, TemporalTrustLevel.OBSERVED);
        SecurityStatusVersion existing = statusVersion(
                31, 20, KNOWN_2, null, true, TemporalTrustLevel.OBSERVED);

        when(history.findByIdForUpdate(30)).thenReturn(Optional.of(superseded));
        when(datasets.findById(1)).thenReturn(Optional.of(dataset));
        when(events.findById(20)).thenReturn(Optional.of(replacementEvent));
        when(hasher.hash(replacement)).thenReturn(HASH);
        when(history.findByIdempotencyKey(replacement)).thenReturn(Optional.of(existing));

        assertEquals(existing, service.correctSecurityStatusVersion(
                new CorrectSecurityStatusVersionCommand(30, replacement)));
        verify(history, never()).closeKnowledgeInterval(30, KNOWN_2);
        verify(history, never()).insertIfAbsent(replacement, HASH, RECORDED);
    }

    @Test
    void returnsSameCalendarCorrectionAfterAnotherCallerClosedAndInserted() {
        DatasetVersion dataset = dataset(1, TemporalTrustLevel.OBSERVED);
        TradingCalendarRevision superseded = calendar(
                40, KNOWN_1, KNOWN_2, true, TemporalTrustLevel.OBSERVED, "1");
        AppendTradingCalendarRevisionCommand replacement = calendarCommand(
                KNOWN_2, false, TemporalTrustLevel.OBSERVED, "2");
        TradingCalendarRevision existing = calendar(
                41, KNOWN_2, null, false, TemporalTrustLevel.OBSERVED, "2");

        when(calendars.findByIdForUpdate(40)).thenReturn(Optional.of(superseded));
        when(datasets.findById(1)).thenReturn(Optional.of(dataset));
        when(calendars.findByIdempotencyKey(replacement)).thenReturn(Optional.of(existing));

        assertEquals(existing, service.correctTradingCalendarRevision(
                new CorrectTradingCalendarRevisionCommand(40, replacement)));
        verify(calendars, never()).closeKnowledgeInterval(40, KNOWN_2);
        verify(calendars, never()).insertIfAbsent(replacement, RECORDED);
    }

    @Test
    void keepsInferredProvenanceOutsidePointInTimeCandidatesOnRead() {
        DatasetVersion dataset = dataset(1, TemporalTrustLevel.BACKFILLED_INFERRED);
        SecurityStatusEvent event = event(
                10, null, KNOWN_1, TemporalTrustLevel.BACKFILLED_INFERRED);
        SecurityStatusVersion version = statusVersion(
                30, 10, KNOWN_1, null, false, TemporalTrustLevel.BACKFILLED_INFERRED);
        Instant cutoff = KNOWN_1.plusSeconds(1);
        when(history.findAsOfCandidates("600000", VALID_FROM, cutoff))
                .thenReturn(List.of(version));
        when(datasets.findById(1)).thenReturn(Optional.of(dataset));
        when(events.findById(10)).thenReturn(Optional.of(event));

        var result = service.findSecurityStatusAsOf("600000", VALID_FROM, cutoff)
                .orElseThrow();

        assertFalse(result.trustAssessment().pointInTimeCandidate());
        assertEquals(TemporalTrustLevel.BACKFILLED_INFERRED,
                result.trustAssessment().trustLevel());
    }

    private static DatasetVersion dataset(long id, TemporalTrustLevel trust) {
        return new DatasetVersion(
                id, "TEMPORAL", SOURCE, VERSION, "connector-v1",
                VALID_FROM, VALID_FROM.plusYears(1), KNOWN_1, RECORDED,
                HASH, trust, new ObjectMapper().createObjectNode());
    }

    private static AppendSecurityStatusEventCommand eventCommand(
            long ignoredId,
            Long supersedes,
            Instant knownAt,
            TemporalTrustLevel trust
    ) {
        return new AppendSecurityStatusEventCommand(
                1, "600000", SecurityStatusEventType.FULL_STATUS_SNAPSHOT,
                VALID_FROM, null, knownAt.minusSeconds(1), knownAt,
                SOURCE, VERSION, "event-" + ignoredId, "1", trust,
                new ObjectMapper().createObjectNode(), HASH, supersedes);
    }

    private static SecurityStatusEvent event(
            long id,
            Long supersedes,
            Instant knownAt,
            TemporalTrustLevel trust
    ) {
        return new SecurityStatusEvent(
                id, 1, "600000", SecurityStatusEventType.FULL_STATUS_SNAPSHOT,
                VALID_FROM, null, knownAt.minusSeconds(1), knownAt, RECORDED,
                SOURCE, VERSION, "event-" + id, "1", trust,
                new ObjectMapper().createObjectNode(), HASH, supersedes);
    }

    private static PublishSecurityStatusVersionCommand statusCommand(
            long eventId,
            Instant knownFrom,
            boolean st,
            TemporalTrustLevel trust
    ) {
        return new PublishSecurityStatusVersionCommand(
                "600000", MarketExchange.SSE, "MAIN", true, true, st,
                VALID_FROM, null, knownFrom, null, eventId, 1,
                SOURCE, VERSION, trust);
    }

    private static SecurityStatusVersion statusVersion(
            long id,
            long eventId,
            Instant knownFrom,
            Instant knownTo,
            boolean st,
            TemporalTrustLevel trust
    ) {
        return new SecurityStatusVersion(
                id, "600000", MarketExchange.SSE, "MAIN", true, true, st,
                VALID_FROM, null, knownFrom, knownTo, eventId, 1,
                SOURCE, VERSION, trust, HASH, RECORDED);
    }

    private static AppendTradingCalendarRevisionCommand calendarCommand(
            Instant knownFrom,
            boolean open,
            TemporalTrustLevel trust,
            String revision
    ) {
        LocalDate date = LocalDate.of(2025, 1, 6);
        return new AppendTradingCalendarRevisionCommand(
                1, MarketExchange.SSE, date, open,
                open ? TradingSessionType.REGULAR : TradingSessionType.TEMPORARY_CLOSURE,
                open ? Instant.parse("2025-01-06T01:30:00Z") : null,
                open ? Instant.parse("2025-01-06T07:00:00Z") : null,
                null, null, knownFrom, null, SOURCE, VERSION,
                "calendar-1", revision, trust, HASH);
    }

    private static TradingCalendarRevision calendar(
            long id,
            Instant knownFrom,
            Instant knownTo,
            boolean open,
            TemporalTrustLevel trust,
            String revision
    ) {
        AppendTradingCalendarRevisionCommand command = calendarCommand(
                knownFrom, open, trust, revision);
        return new TradingCalendarRevision(
                id, command.datasetVersionId(), command.exchange(), command.tradeDate(),
                command.open(), command.sessionType(), command.sessionOpenAt(),
                command.sessionCloseAt(), command.previousOpenDate(), command.nextOpenDate(),
                command.knownFrom(), knownTo, command.source(), command.sourceVersion(),
                command.sourceRecordId(), command.sourceRevision(), command.trustLevel(),
                command.payloadHash(), RECORDED);
    }
}

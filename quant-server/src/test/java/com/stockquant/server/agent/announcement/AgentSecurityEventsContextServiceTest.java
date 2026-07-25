package com.stockquant.server.agent.announcement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.announcement.AnnouncementCanonicalService.AnnouncementFact;
import com.stockquant.server.agent.announcement.AnnouncementProviderModels.ProviderRecord;
import com.stockquant.server.agent.announcement.AnnouncementRepository.CaptureBatchRecord;
import com.stockquant.server.agent.announcement.AnnouncementRepository.ObservationRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentSecurityEventsContextServiceTest {

    private static final String SYMBOL = "000001";
    private static final LocalDate REQUEST_DATE = LocalDate.of(2025, 6, 30);
    private static final LocalDate LOOKBACK_START = REQUEST_DATE.minusDays(179);
    private static final Instant QUERIED_AT =
            Instant.parse("2025-06-30T08:00:00Z");
    private static final Instant CAPTURED_AT =
            Instant.parse("2025-06-30T07:00:00Z");
    private static final String BATCH =
            "ANNOUNCEMENT_BATCH_V1:11111111-1111-1111-1111-111111111111";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AnnouncementRepository repository = mock(AnnouncementRepository.class);
    private final AnnouncementCanonicalService canonical =
            new AnnouncementCanonicalService(mapper);
    private AgentSecurityEventsContextService service;

    @BeforeEach
    void setUp() {
        service = new AgentSecurityEventsContextService(
                mapper, repository, canonical);
    }

    @Test
    void completeFreshZeroEventCaptureIsAvailable() {
        when(repository.findBatches(SYMBOL, QUERIED_AT))
                .thenReturn(List.of(batch(
                        true, LOOKBACK_START, REQUEST_DATE, CAPTURED_AT)));
        when(repository.findAsOf(
                SYMBOL, LOOKBACK_START, REQUEST_DATE, QUERIED_AT))
                .thenReturn(List.of());

        ObjectNode result = service.create(SYMBOL, REQUEST_DATE, QUERIED_AT);

        assertTrue(result.path("available").asBoolean());
        assertTrue(result.path("completeCapture").asBoolean());
        assertEquals(0, result.path("eventCount").asInt());
        assertEquals(0, result.withArray("events").size());
        assertEquals("RESEARCH", result.path("assuranceLevel").asText());
        assertFalse(result.path("formalEligible").asBoolean());
        assertFalse(result.path("pitVerified").asBoolean());
        assertFalse(result.path("revisionRelationshipGuaranteed").asBoolean());
    }

    @Test
    void selectsAndValidatesLatestVisibleObservation() {
        ObservationRecord event = observation(
                "1212345678", "立案调查公告", REQUEST_DATE.minusDays(1), CAPTURED_AT);
        when(repository.findBatches(SYMBOL, QUERIED_AT))
                .thenReturn(List.of(batch(
                        true, LOOKBACK_START, REQUEST_DATE, CAPTURED_AT)));
        when(repository.findAsOf(
                SYMBOL, LOOKBACK_START, REQUEST_DATE, QUERIED_AT))
                .thenReturn(List.of(event));

        ObjectNode result = service.create(SYMBOL, REQUEST_DATE, QUERIED_AT);

        assertTrue(result.path("available").asBoolean());
        assertEquals(1, result.path("eventCount").asInt());
        assertEquals(
                "CNINFO:1212345678",
                result.withArray("events").get(0)
                        .path("sourceAnnouncementId").asText());
        assertEquals(
                event.canonicalContentHash(),
                result.withArray("events").get(0)
                        .path("canonicalContentHash").asText());
    }

    @Test
    void noCompleteRangeAndStaleCapturesHaveDistinctReasons() {
        when(repository.findBatches(SYMBOL, QUERIED_AT)).thenReturn(List.of());
        assertReason(
                AnnouncementContracts.NO_COMPLETE_CAPTURE,
                service.create(SYMBOL, REQUEST_DATE, QUERIED_AT));

        when(repository.findBatches(SYMBOL, QUERIED_AT)).thenReturn(List.of(
                batch(false, LOOKBACK_START, REQUEST_DATE, CAPTURED_AT)));
        assertReason(
                AnnouncementContracts.NO_COMPLETE_CAPTURE,
                service.create(SYMBOL, REQUEST_DATE, QUERIED_AT));

        when(repository.findBatches(SYMBOL, QUERIED_AT)).thenReturn(List.of(
                batch(true, LOOKBACK_START.plusDays(1), REQUEST_DATE, CAPTURED_AT)));
        assertReason(
                AnnouncementContracts.CAPTURE_RANGE_INCOMPLETE,
                service.create(SYMBOL, REQUEST_DATE, QUERIED_AT));

        when(repository.findBatches(SYMBOL, QUERIED_AT)).thenReturn(List.of(
                batch(
                        true,
                        LOOKBACK_START,
                        REQUEST_DATE,
                        QUERIED_AT.minusSeconds(24 * 3600L + 1))));
        assertReason(
                AnnouncementContracts.CAPTURE_STALE,
                service.create(SYMBOL, REQUEST_DATE, QUERIED_AT));
    }

    @Test
    void invalidObservationNeverProducesAvailableContext() {
        ObservationRecord valid = observation(
                "1212345678", "问询函", REQUEST_DATE.minusDays(1), CAPTURED_AT);
        ObservationRecord corrupted = new ObservationRecord(
                valid.sourceAnnouncementId(),
                valid.sourceIdentityStrength(),
                valid.symbol(),
                valid.securityName(),
                valid.title(),
                valid.reportedPublishDate(),
                valid.sourceUrl(),
                valid.normalizedSourceUrl(),
                valid.sourceUrlHash(),
                valid.firstObservedAt(),
                valid.knownAt(),
                "0".repeat(64),
                valid.observationVersion(),
                valid.batchVersion(),
                valid.sourceCode(),
                valid.providerContractVersion(),
                valid.assuranceLevel(),
                valid.formalEligible(),
                valid.pitVerified(),
                valid.revisionRelationshipGuaranteed(),
                valid.reportedPublishTimePrecision(),
                valid.rawPayload());
        when(repository.findBatches(SYMBOL, QUERIED_AT))
                .thenReturn(List.of(batch(
                        true, LOOKBACK_START, REQUEST_DATE, CAPTURED_AT)));
        when(repository.findAsOf(
                SYMBOL, LOOKBACK_START, REQUEST_DATE, QUERIED_AT))
                .thenReturn(List.of(corrupted));

        assertReason(
                AnnouncementContracts.CONTEXT_INVALID,
                service.create(SYMBOL, REQUEST_DATE, QUERIED_AT));
    }

    @Test
    void mockedObservationOutsideLookbackNeverProducesAvailableContext() {
        ObservationRecord outside = observation(
                "1212345678", "问询函", LOOKBACK_START.minusDays(1), CAPTURED_AT);
        when(repository.findBatches(SYMBOL, QUERIED_AT))
                .thenReturn(List.of(batch(
                        true, LOOKBACK_START, REQUEST_DATE, CAPTURED_AT)));
        when(repository.findAsOf(
                SYMBOL, LOOKBACK_START, REQUEST_DATE, QUERIED_AT))
                .thenReturn(List.of(outside));

        assertReason(
                AnnouncementContracts.CONTEXT_INVALID,
                service.create(SYMBOL, REQUEST_DATE, QUERIED_AT));
    }

    @Test
    void futureDateIsRejectedWithoutDatabaseRead() {
        ObjectNode result = service.create(
                SYMBOL, REQUEST_DATE.plusDays(1), QUERIED_AT);
        assertReason(AnnouncementContracts.FUTURE_REQUEST_DATE, result);
        verify(repository, never()).findBatches(any(), any());
        verify(repository, never()).findAsOf(any(), any(), any(), any());
    }

    @Test
    void historicalRequestUsesShanghaiEndOfDayCutoff() {
        Instant queriedLater = Instant.parse("2025-07-02T01:00:00Z");
        Instant historicalCutoff =
                Instant.parse("2025-06-30T15:59:59.999999Z");
        Instant captured = historicalCutoff.minusSeconds(3600);
        when(repository.findBatches(SYMBOL, historicalCutoff))
                .thenReturn(List.of(batch(
                        true, LOOKBACK_START, REQUEST_DATE, captured)));
        when(repository.findAsOf(
                SYMBOL, LOOKBACK_START, REQUEST_DATE, historicalCutoff))
                .thenReturn(List.of());

        ObjectNode result = service.create(SYMBOL, REQUEST_DATE, queriedLater);

        assertTrue(result.path("available").asBoolean());
        assertEquals(
                historicalCutoff.toString(),
                result.path("knowledgeCutoff").asText());
        verify(repository).findAsOf(
                eq(SYMBOL),
                eq(LOOKBACK_START),
                eq(REQUEST_DATE),
                eq(historicalCutoff));
    }

    private CaptureBatchRecord batch(
            boolean complete,
            LocalDate start,
            LocalDate end,
            Instant observedAt
    ) {
        return new CaptureBatchRecord(
                1L,
                BATCH,
                AnnouncementContracts.SOURCE_CODE,
                AnnouncementContracts.PROVIDER_CONTRACT_VERSION,
                SYMBOL,
                start,
                end,
                observedAt,
                complete,
                6,
                complete ? 6 : 5,
                0,
                0);
    }

    private ObservationRecord observation(
            String id,
            String title,
            LocalDate date,
            Instant observedAt
    ) {
        String url = "https://static.cninfo.com.cn/finalpage/"
                + date + "/" + id + ".pdf";
        ObjectNode raw = mapper.createObjectNode();
        raw.put("代码", SYMBOL);
        raw.put("简称", "平安银行");
        raw.put("公告标题", title);
        raw.put("公告时间", date.toString());
        raw.put("公告链接", url);
        AnnouncementFact fact = canonical.prepare(new ProviderRecord(
                SYMBOL,
                "平安银行",
                title,
                date,
                url,
                raw), observedAt, BATCH);
        return new ObservationRecord(
                fact.sourceAnnouncementId(),
                fact.sourceIdentityStrength(),
                fact.symbol(),
                fact.securityName(),
                fact.title(),
                fact.reportedPublishDate(),
                fact.sourceUrl(),
                fact.normalizedSourceUrl(),
                fact.sourceUrlHash(),
                fact.firstObservedAt(),
                fact.firstObservedAt(),
                fact.canonicalContentHash(),
                fact.observationVersion(),
                BATCH,
                AnnouncementContracts.SOURCE_CODE,
                AnnouncementContracts.PROVIDER_CONTRACT_VERSION,
                AnnouncementContracts.ASSURANCE_LEVEL,
                false,
                false,
                false,
                AnnouncementContracts.PUBLISH_TIME_PRECISION,
                raw);
    }

    private static void assertReason(String expected, ObjectNode context) {
        assertFalse(context.path("available").asBoolean());
        assertEquals(expected, context.path("reasonCode").asText());
        assertEquals(0, context.path("eventCount").asInt());
        assertTrue(context.withArray("events").isEmpty());
    }
}

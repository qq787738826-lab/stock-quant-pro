package com.stockquant.server.agent.announcement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.announcement.AnnouncementProviderModels.CaptureRequest;
import com.stockquant.server.agent.announcement.AnnouncementProviderModels.CaptureResult;
import com.stockquant.server.agent.announcement.AnnouncementProviderModels.ProviderError;
import com.stockquant.server.agent.announcement.AnnouncementProviderModels.ProviderRecord;
import com.stockquant.server.agent.announcement.AnnouncementProviderModels.ProviderRequest;
import com.stockquant.server.agent.announcement.AnnouncementProviderModels.ProviderResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnnouncementIngestionServiceTest {

    private static final Instant NOW =
            Instant.parse("2025-07-01T02:03:04.123456Z");
    private static final LocalDate START = LocalDate.of(2025, 6, 1);
    private static final LocalDate END = LocalDate.of(2025, 6, 30);

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AnnouncementProviderClient provider = mock(
            AnnouncementProviderClient.class);
    private final AnnouncementCaptureTransaction transaction = mock(
            AnnouncementCaptureTransaction.class);
    private final AnnouncementProperties properties = new AnnouncementProperties();
    private AnnouncementIngestionService service;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setBaseUrl("http://127.0.0.1:8001");
        service = new AnnouncementIngestionService(
                properties,
                provider,
                new AnnouncementCanonicalService(mapper),
                transaction,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void validatesProviderResponseBeforePersistingCompleteCapture() {
        ProviderResponse response = response(
                START,
                END,
                true,
                1,
                1,
                List.of(record("1212345678", "重大诉讼公告", END)),
                List.of());
        when(provider.fetch(any())).thenReturn(response);
        when(transaction.persist(
                any(), eq("000001"), eq(START), eq(END), eq(NOW),
                eq(response), anyList()))
                .thenAnswer(invocation -> new CaptureResult(
                        invocation.getArgument(0),
                        AnnouncementContracts.SOURCE_CODE,
                        AnnouncementContracts.PROVIDER_CONTRACT_VERSION,
                        "000001",
                        START,
                        END,
                        true,
                        1,
                        1,
                        1,
                        1));

        CaptureResult result = service.capture(
                new CaptureRequest("000001", START, END));

        assertEquals(1, result.recordCount());
        assertEquals(1, result.appendedCount());
        ArgumentCaptor<ProviderRequest> request =
                ArgumentCaptor.forClass(ProviderRequest.class);
        verify(provider).fetch(request.capture());
        assertEquals("沪深京", request.getValue().market());
        assertEquals("", request.getValue().keyword());
        assertEquals("", request.getValue().category());
        verify(transaction).persist(
                any(), eq("000001"), eq(START), eq(END), eq(NOW),
                eq(response), anyList());
    }

    @Test
    void persistsPartialCaptureAsIncompleteCoverageEvidence() {
        LocalDate end = START.plusDays(30);
        ProviderResponse response = response(
                START,
                end,
                false,
                2,
                1,
                List.of(record("1212345678", "问询函", START)),
                List.of(new ProviderError(
                        "AKSHARE_PROVIDER_TEMPORARY_FAILURE",
                        START.plusDays(30),
                        end,
                        3)));
        when(provider.fetch(any())).thenReturn(response);
        when(transaction.persist(
                any(), eq("000001"), eq(START), eq(end), eq(NOW),
                eq(response), anyList()))
                .thenAnswer(invocation -> new CaptureResult(
                        invocation.getArgument(0),
                        AnnouncementContracts.SOURCE_CODE,
                        AnnouncementContracts.PROVIDER_CONTRACT_VERSION,
                        "000001",
                        START,
                        end,
                        false,
                        2,
                        1,
                        1,
                        1));

        CaptureResult result = service.capture(
                new CaptureRequest("000001", START, end));

        assertEquals(false, result.complete());
        verify(transaction).persist(
                any(), eq("000001"), eq(START), eq(end), eq(NOW),
                eq(response), anyList());
    }

    @Test
    void disabledEntryPointNeverCallsProvider() {
        properties.setEnabled(false);
        assertThrows(
                IllegalStateException.class,
                () -> service.capture(new CaptureRequest("000001", START, END)));
        verify(provider, never()).fetch(any());
        verify(transaction, never()).persist(
                any(), any(), any(), any(), any(), any(), anyList());
    }

    @Test
    void rejectsWrongVersionRangeFutureDateDuplicateAndUnstableOrder() {
        ProviderResponse wrongVersion = new ProviderResponse(
                "WRONG",
                AnnouncementContracts.AKSHARE_VERSION,
                "000001",
                START,
                END,
                true,
                1,
                1,
                List.of(),
                List.of());
        when(provider.fetch(any())).thenReturn(wrongVersion);
        assertThrows(
                IllegalArgumentException.class,
                () -> service.capture(new CaptureRequest("000001", START, END)));

        ProviderRecord latest = record("1212345679", "问询函", END);
        ProviderRecord earlier = record("1212345678", "重大诉讼", START);
        when(provider.fetch(any())).thenReturn(response(
                START, END, true, 1, 1, List.of(latest, earlier), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.capture(new CaptureRequest("000001", START, END)));

        when(provider.fetch(any())).thenReturn(response(
                START,
                END,
                true,
                1,
                1,
                List.of(earlier, earlier),
                List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.capture(new CaptureRequest("000001", START, END)));

        when(provider.fetch(any())).thenReturn(response(
                START,
                END,
                true,
                1,
                1,
                List.of(record("1212345680", "未来公告", NOW
                        .atZone(AnnouncementContracts.MARKET_ZONE)
                        .toLocalDate().plusDays(1))),
                List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.capture(new CaptureRequest(
                        "000001", START, NOW.atZone(
                        AnnouncementContracts.MARKET_ZONE).toLocalDate().plusDays(1))));
        verify(transaction, never()).persist(
                any(), any(), any(), any(), any(), any(), anyList());
    }

    @Test
    void validatesLogicalDateRangeBeforeProviderCall() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.capture(new CaptureRequest(
                        "000001", START, START.plusDays(366))));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.capture(new CaptureRequest(
                        "ABC", START, END)));
        verify(provider, never()).fetch(any());
    }

    private ProviderResponse response(
            LocalDate start,
            LocalDate end,
            boolean complete,
            int chunks,
            int successful,
            List<ProviderRecord> records,
            List<ProviderError> errors
    ) {
        return new ProviderResponse(
                AnnouncementContracts.PROVIDER_CONTRACT_VERSION,
                AnnouncementContracts.AKSHARE_VERSION,
                "000001",
                start,
                end,
                complete,
                chunks,
                successful,
                records,
                errors);
    }

    private ProviderRecord record(
            String id,
            String title,
            LocalDate reportedDate
    ) {
        String url = "https://static.cninfo.com.cn/finalpage/"
                + reportedDate + "/" + id + ".pdf";
        ObjectNode raw = mapper.createObjectNode();
        raw.put("代码", "000001");
        raw.put("简称", "平安银行");
        raw.put("公告标题", title);
        raw.put("公告时间", reportedDate.toString());
        raw.put("公告链接", url);
        return new ProviderRecord(
                "000001",
                "平安银行",
                title,
                reportedDate,
                url,
                raw);
    }
}

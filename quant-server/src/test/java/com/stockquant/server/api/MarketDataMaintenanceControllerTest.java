package com.stockquant.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.production.AutomaticMarketDataMaintenanceDispatchGateway;
import com.stockquant.server.production.AutomaticMarketDataMaintenanceProperties;
import com.stockquant.server.production.AutomaticMarketDataMaintenanceRuntimeState;
import com.stockquant.server.production.AutomaticMarketDataMaintenanceStatusQuery;
import com.stockquant.server.production.ManualMarketDataMaintenanceService;
import com.stockquant.server.production.ManualMarketDataTargetResolver;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MarketDataMaintenanceControllerTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 21);
    private static final LocalDate TARGET = LocalDate.of(2026, 10, 20);
    private static final String REQUEST_ID =
            "SQHB_20261020T083000Z_123456789ABC";

    @Test
    void exposesResolvedTargetForPostAndGetBeforeCutoff() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<LocalDate> dispatchedTarget = new AtomicReference<>();
        AutomaticMarketDataMaintenanceDispatchGateway gateway =
                (date, submittedAt) -> {
                    calls.incrementAndGet();
                    dispatchedTarget.set(date);
                    return new AutomaticMarketDataMaintenanceDispatchGateway
                            .DispatchResult(REQUEST_ID, true,
                            "AUTOMATIC_MARKET_DATA_MAINTENANCE_SUBMITTED");
                };
        AutomaticMarketDataMaintenanceStatusQuery query = target ->
                AutomaticMarketDataMaintenanceStatusQuery.Snapshot.notRun(
                        target, REQUEST_ID,
                        "AUTOMATIC_MARKET_DATA_MAINTENANCE_NOT_RUN");
        MockMvc mvc = mvc(gateway, query, resolved(TARGET));

        mvc.perform(post("/api/data/maintenance/sync-latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.requestId").value(REQUEST_ID))
                .andExpect(jsonPath("$.data.tradeDate")
                        .value("2026-10-20"))
                .andExpect(jsonPath("$.data.effectiveTargetTradeDate")
                        .value("2026-10-20"));
        mvc.perform(get("/api/data/maintenance/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.tradeDate")
                        .value("2026-10-20"))
                .andExpect(jsonPath("$.data.effectiveTargetTradeDate")
                        .value("2026-10-20"))
                .andExpect(jsonPath("$.data.readyForShadow").value(false));
        assertEquals(TARGET, dispatchedTarget.get());
        assertEquals(1, calls.get());
    }

    @Test
    void unresolvedCalendarReturnsFailClosedReasonWithoutDispatch()
            throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AutomaticMarketDataMaintenanceDispatchGateway gateway =
                (date, submittedAt) -> {
                    calls.incrementAndGet();
                    throw new AssertionError("dispatch must not be called");
                };
        AutomaticMarketDataMaintenanceStatusQuery query = target -> {
            throw new AssertionError("status lookup must not be called");
        };
        ManualMarketDataTargetResolver resolver = mock(
                ManualMarketDataTargetResolver.class);
        when(resolver.resolve(any())).thenThrow(new IllegalStateException(
                "MANUAL_MARKET_DATA_TARGET_TRADE_DATE_UNAVAILABLE"));
        MockMvc mvc = mvc(gateway, query, resolver);

        mvc.perform(post("/api/data/maintenance/sync-latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NOT_RUN"))
                .andExpect(jsonPath("$.data.requestId").value("NONE"))
                .andExpect(jsonPath("$.data.reason").value(
                        "MANUAL_MARKET_DATA_TARGET_TRADE_DATE_UNAVAILABLE"))
                .andExpect(jsonPath("$.data.effectiveTargetTradeDate")
                        .doesNotExist());
        assertEquals(0, calls.get());
    }

    @Test
    void httpClientCannotChooseSubmissionModeOrTarget() throws Exception {
        AtomicReference<LocalDate> dispatchedTarget = new AtomicReference<>();
        AutomaticMarketDataMaintenanceDispatchGateway gateway =
                (date, submittedAt) -> {
                    dispatchedTarget.set(date);
                    return new AutomaticMarketDataMaintenanceDispatchGateway
                            .DispatchResult(REQUEST_ID, true,
                            "AUTOMATIC_MARKET_DATA_MAINTENANCE_SUBMITTED");
                };
        AutomaticMarketDataMaintenanceStatusQuery query = target ->
                AutomaticMarketDataMaintenanceStatusQuery.Snapshot.notRun(
                        target, REQUEST_ID,
                        "AUTOMATIC_MARKET_DATA_MAINTENANCE_NOT_RUN");
        MockMvc mvc = mvc(gateway, query, resolved(TARGET));

        mvc.perform(post("/api/data/maintenance/sync-latest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"submission":{"mode":"AUTOMATIC"},
                                 "targetTradeDate":"2099-12-31"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.effectiveTargetTradeDate")
                        .value("2026-10-20"));

        assertEquals(TARGET, dispatchedTarget.get());
    }

    private static MockMvc mvc(
            AutomaticMarketDataMaintenanceDispatchGateway gateway,
            AutomaticMarketDataMaintenanceStatusQuery query,
            ManualMarketDataTargetResolver resolver
    ) {
        var properties = new AutomaticMarketDataMaintenanceProperties();
        properties.setEnabled(true);
        var service = new ManualMarketDataMaintenanceService(
                explicitManualGateway(gateway),
                query, properties, Clock.fixed(
                Instant.parse("2026-10-21T03:00:00Z"), ZoneOffset.UTC),
                new AutomaticMarketDataMaintenanceRuntimeState(), resolver);
        return MockMvcBuilders.standaloneSetup(
                        new MarketDataMaintenanceController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        new ObjectMapper().findAndRegisterModules()))
                .build();
    }

    private static AutomaticMarketDataMaintenanceDispatchGateway
    explicitManualGateway(
            AutomaticMarketDataMaintenanceDispatchGateway manualDelegate
    ) {
        return new AutomaticMarketDataMaintenanceDispatchGateway() {
            @Override
            public DispatchResult dispatch(
                    LocalDate tradeDate,
                    Instant submittedAt
            ) {
                throw new AssertionError(
                        "automatic dispatch must not be called");
            }

            @Override
            public DispatchResult dispatchManual(
                    LocalDate effectiveTargetTradeDate,
                    Instant submittedAt
            ) {
                return manualDelegate.dispatch(effectiveTargetTradeDate,
                        submittedAt);
            }
        };
    }

    private static ManualMarketDataTargetResolver resolved(
            LocalDate target
    ) {
        ManualMarketDataTargetResolver resolver = mock(
                ManualMarketDataTargetResolver.class);
        when(resolver.resolve(any())).thenReturn(target);
        return resolver;
    }
}

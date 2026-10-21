package com.stockquant.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.production.AutomaticMarketDataMaintenanceDispatchGateway;
import com.stockquant.server.production.AutomaticMarketDataMaintenanceProperties;
import com.stockquant.server.production.AutomaticMarketDataMaintenanceRuntimeState;
import com.stockquant.server.production.AutomaticMarketDataMaintenanceStatusQuery;
import com.stockquant.server.production.ManualMarketDataMaintenanceService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MarketDataMaintenanceControllerTest {
    private static final LocalDate DATE = LocalDate.of(2026, 10, 21);
    private static final String REQUEST_ID =
            "SQHB_20261021T083000Z_123456789ABC";

    @Test
    void exposesPostAndGetWithoutLegacyUpdateService() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AutomaticMarketDataMaintenanceDispatchGateway gateway =
                (date, submittedAt) -> {
                    calls.incrementAndGet();
                    return new AutomaticMarketDataMaintenanceDispatchGateway
                            .DispatchResult(REQUEST_ID, true,
                            "AUTOMATIC_MARKET_DATA_MAINTENANCE_SUBMITTED");
                };
        AutomaticMarketDataMaintenanceStatusQuery query = ignored ->
                AutomaticMarketDataMaintenanceStatusQuery.Snapshot.notRun(
                        DATE, REQUEST_ID,
                        "AUTOMATIC_MARKET_DATA_MAINTENANCE_NOT_RUN");
        var properties = new AutomaticMarketDataMaintenanceProperties();
        properties.setEnabled(true);
        var service = new ManualMarketDataMaintenanceService(gateway,
                query, properties, Clock.fixed(
                Instant.parse("2026-10-21T08:30:00Z"), ZoneOffset.UTC),
                new AutomaticMarketDataMaintenanceRuntimeState());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new MarketDataMaintenanceController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        new ObjectMapper().findAndRegisterModules()))
                .build();

        mvc.perform(post("/api/data/maintenance/sync-latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.requestId").value(REQUEST_ID))
                .andExpect(jsonPath("$.data.tradeDate")
                        .value("2026-10-21"));
        mvc.perform(get("/api/data/maintenance/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.readyForShadow").value(false));
        assertEquals(1, calls.get());
    }

    @Test
    void outsideWindowReturnsExplicitReasonWithoutDispatch() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AutomaticMarketDataMaintenanceDispatchGateway gateway =
                (date, submittedAt) -> {
                    calls.incrementAndGet();
                    throw new AssertionError("dispatch must not be called");
                };
        AutomaticMarketDataMaintenanceStatusQuery query = ignored -> {
            throw new AssertionError("status lookup must not be called");
        };
        var properties = new AutomaticMarketDataMaintenanceProperties();
        properties.setEnabled(true);
        var service = new ManualMarketDataMaintenanceService(gateway,
                query, properties, Clock.fixed(
                Instant.parse("2026-10-21T03:00:00Z"), ZoneOffset.UTC),
                new AutomaticMarketDataMaintenanceRuntimeState());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new MarketDataMaintenanceController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        new ObjectMapper().findAndRegisterModules()))
                .build();

        mvc.perform(post("/api/data/maintenance/sync-latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NOT_RUN"))
                .andExpect(jsonPath("$.data.requestId").value("NONE"))
                .andExpect(jsonPath("$.data.reason").value(
                        "AUTOMATIC_MARKET_DATA_MAINTENANCE_WINDOW_INVALID"));
        assertEquals(0, calls.get());
    }
}

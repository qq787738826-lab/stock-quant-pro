package com.stockquant.server.agent.shadow.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.shadow.AgentShadowBatchService;
import com.stockquant.server.agent.shadow.AgentShadowContracts;
import com.stockquant.server.agent.shadow.AgentShadowMetricsService;
import com.stockquant.server.agent.shadow.AgentShadowModels.BatchStatus;
import com.stockquant.server.agent.shadow.AgentShadowModels.SelectionMode;
import com.stockquant.server.agent.shadow.AgentShadowModels.ShadowBatch;
import com.stockquant.server.agent.shadow.AgentShadowModels.TriggerMode;
import com.stockquant.server.agent.shadow.AgentShadowReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentShadowControllerTest {

    private AgentShadowBatchService batchService;
    private AgentShadowMetricsService metricsService;
    private AgentShadowReviewService reviewService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        batchService = mock(AgentShadowBatchService.class);
        metricsService = mock(AgentShadowMetricsService.class);
        reviewService = mock(AgentShadowReviewService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new AgentShadowController(
                                batchService,
                                metricsService,
                                reviewService))
                .setControllerAdvice(
                        new AgentShadowExceptionHandler())
                .build();
    }

    @Test
    void createsStrictExplicitBatch() throws Exception {
        when(batchService.createManual(
                any(), any(), any(), any(), any()))
                .thenReturn(batch());

        mockMvc.perform(post("/api/agent-team/shadow/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tradeDate":"2026-07-27",
                                  "selectionMode":"EXPLICIT",
                                  "explicitSymbols":["600001","600002"],
                                  "maxSymbols":10,
                                  "createdBy":"local-reviewer"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.ruleVersion").value(
                        AgentShadowContracts.RULE_VERSION));

        verify(batchService).createManual(
                LocalDate.of(2026, 7, 27),
                SelectionMode.EXPLICIT,
                List.of("600001", "600002"),
                10,
                "local-reviewer");
    }

    @Test
    void rejectsInvalidSymbolsAndLimitsAtApiBoundary()
            throws Exception {
        mockMvc.perform(post("/api/agent-team/shadow/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tradeDate":"2026-07-27",
                                  "selectionMode":"EXPLICIT",
                                  "explicitSymbols":["bad"],
                                  "maxSymbols":21,
                                  "createdBy":"reviewer"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void exposesBatchItemsCancelMetricsDriftAndReviews()
            throws Exception {
        when(batchService.batch(1)).thenReturn(batch());
        when(batchService.items(1)).thenReturn(List.of());
        when(batchService.cancel(1)).thenReturn(batch());
        when(reviewService.reviews(9)).thenReturn(List.of());

        mockMvc.perform(get("/api/agent-team/shadow/batches/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1));
        mockMvc.perform(get(
                        "/api/agent-team/shadow/batches/1/items"))
                .andExpect(status().isOk());
        mockMvc.perform(post(
                        "/api/agent-team/shadow/batches/1/cancel"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/agent-team/shadow/metrics"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/agent-team/shadow/drift"))
                .andExpect(status().isOk());
        mockMvc.perform(get(
                        "/api/agent-team/shadow/items/9/reviews"))
                .andExpect(status().isOk());
    }

    private static ShadowBatch batch() {
        return new ShadowBatch(
                1,
                AgentShadowContracts.RUN_CONTROL_VERSION,
                BatchStatus.QUEUED,
                TriggerMode.MANUAL,
                LocalDate.of(2026, 7, 27),
                AgentShadowContracts.RULE_VERSION,
                SelectionMode.EXPLICIT,
                "a".repeat(64),
                10,
                2,
                0, 0, 0, 0, 0, 0, 0, 0,
                false,
                new ObjectMapper().createObjectNode(),
                null,
                null,
                null,
                "reviewer",
                Instant.EPOCH,
                Instant.EPOCH);
    }
}

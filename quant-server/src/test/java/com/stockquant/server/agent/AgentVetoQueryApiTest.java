package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stockquant.server.agent.api.AgentTaskController;
import com.stockquant.server.agent.api.AgentTaskExceptionHandler;
import com.stockquant.server.agent.exception.AgentTaskNotFoundException;
import com.stockquant.server.agent.model.AgentModels.FormalVeto;
import com.stockquant.server.agent.model.AgentTypes.AgentCode;
import com.stockquant.server.agent.model.AgentTypes.TaskStatus;
import com.stockquant.server.agent.repository.AgentDecisionRepository;
import com.stockquant.server.agent.repository.AgentEvidenceRepository;
import com.stockquant.server.agent.repository.AgentRunRepository;
import com.stockquant.server.agent.repository.AgentTaskRepository;
import com.stockquant.server.agent.repository.AgentVetoRepository;
import com.stockquant.server.agent.service.AgentCacheService;
import com.stockquant.server.agent.service.AgentContextSnapshotService;
import com.stockquant.server.agent.service.AgentTaskCreationTransaction;
import com.stockquant.server.agent.service.AgentTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AgentVetoQueryApiTest {

    private static final Instant CREATED_AT = Instant.parse("2026-07-14T05:02:00Z");
    private static final FormalVeto VETO = new FormalVeto(
            "37", 77, 106, AgentCode.POSITION_RISK, "EVENT_RISK_LIMIT",
            "风险证据触发仓位风控否决", List.of("e-risk"), CREATED_AT
    );

    @Mock AgentTaskRepository taskRepository;
    @Mock AgentRunRepository runRepository;
    @Mock AgentEvidenceRepository evidenceRepository;
    @Mock AgentDecisionRepository decisionRepository;
    @Mock AgentVetoRepository vetoRepository;
    @Mock AgentCacheService cacheService;
    @Mock AgentContextSnapshotService contextService;
    @Mock AgentTaskCreationTransaction creationTransaction;

    private AgentTaskService service;

    @BeforeEach
    void setUp() {
        service = new AgentTaskService(
                taskRepository, runRepository, evidenceRepository, decisionRepository, vetoRepository,
                cacheService, contextService, creationTransaction
        );
    }

    @Test
    void serviceReturnsPersistedFormalVetoesWithoutSynthesizingThem() {
        when(taskRepository.findById(77)).thenReturn(Optional.of(AgentTestFixtures.task(77, TaskStatus.COMPLETED)));
        when(vetoRepository.findByTaskId(77)).thenReturn(List.of(VETO));

        List<FormalVeto> result = service.vetoes(77);

        assertEquals(List.of(VETO), result);
        verify(vetoRepository).findByTaskId(77);
        verify(runRepository, never()).findByTaskId(77);
        verify(decisionRepository, never()).findByTaskId(77);
    }

    @Test
    void serviceReturnsEmptyListWhenTaskHasNoFormalVeto() {
        when(taskRepository.findById(77)).thenReturn(Optional.of(AgentTestFixtures.task(77, TaskStatus.COMPLETED)));
        when(vetoRepository.findByTaskId(77)).thenReturn(List.of());

        assertEquals(List.of(), service.vetoes(77));
    }

    @Test
    void serviceRejectsMissingTaskBeforeQueryingVetoes() {
        when(taskRepository.findById(404)).thenReturn(Optional.empty());

        assertThrows(AgentTaskNotFoundException.class, () -> service.vetoes(404));
        verify(vetoRepository, never()).findByTaskId(404);
    }

    @Test
    void controllerReturnsCompletePersistedFormalVetoFields() throws Exception {
        AgentTaskService controllerService = mock(AgentTaskService.class);
        when(controllerService.vetoes(77)).thenReturn(List.of(VETO));
        MockMvc mockMvc = mockMvc(controllerService);

        mockMvc.perform(get("/api/agent-tasks/77/vetoes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("OK"))
                .andExpect(jsonPath("$.data[0].vetoId").value("37"))
                .andExpect(jsonPath("$.data[0].taskId").value(77))
                .andExpect(jsonPath("$.data[0].runId").value(106))
                .andExpect(jsonPath("$.data[0].agentCode").value("POSITION_RISK"))
                .andExpect(jsonPath("$.data[0].vetoCode").value("EVENT_RISK_LIMIT"))
                .andExpect(jsonPath("$.data[0].reason").value("风险证据触发仓位风控否决"))
                .andExpect(jsonPath("$.data[0].evidenceIds[0]").value("e-risk"))
                .andExpect(jsonPath("$.data[0].createdAt").value("2026-07-14T05:02:00Z"));
    }

    @Test
    void controllerReturnsJsonEmptyArrayWhenNoFormalVetoExists() throws Exception {
        AgentTaskService controllerService = mock(AgentTaskService.class);
        when(controllerService.vetoes(77)).thenReturn(List.of());

        mockMvc(controllerService).perform(get("/api/agent-tasks/77/vetoes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void controllerUsesExistingNotFoundSemantics() throws Exception {
        AgentTaskService controllerService = mock(AgentTaskService.class);
        when(controllerService.vetoes(404)).thenThrow(new AgentTaskNotFoundException(404));

        mockMvc(controllerService).perform(get("/api/agent-tasks/404/vetoes"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private static MockMvc mockMvc(AgentTaskService service) {
        JsonMapper objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        return MockMvcBuilders.standaloneSetup(new AgentTaskController(service))
                .setControllerAdvice(new AgentTaskExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }
}

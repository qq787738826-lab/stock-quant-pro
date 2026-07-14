package com.stockquant.server.agent;

import com.stockquant.server.agent.api.AgentTaskController;
import com.stockquant.server.agent.api.AgentTaskExceptionHandler;
import com.stockquant.server.agent.exception.AgentTaskNotFoundException;
import com.stockquant.server.agent.exception.AgentTeamException;
import com.stockquant.server.agent.model.AgentModels.CreatedTask;
import com.stockquant.server.agent.model.AgentModels.PageResult;
import com.stockquant.server.agent.model.AgentTypes.TaskStatus;
import com.stockquant.server.agent.service.AgentTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

class AgentTaskControllerTest {

    private AgentTaskService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(AgentTaskService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AgentTaskController(service))
                .setControllerAdvice(new AgentTaskExceptionHandler())
                .build();
    }

    @Test
    void createsAgentTaskThroughJavaApi() throws Exception {
        when(service.create(any(), eq("API"))).thenReturn(new CreatedTask(
                AgentTestFixtures.task(1, TaskStatus.QUEUED), true
        ));

        mockMvc.perform(post("/api/agent-tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "symbol":"600000",
                                  "tradeDate":"2026-07-14",
                                  "executionMode":"LOCAL_RULES",
                                  "ruleVersion":"rules-1",
                                  "forceRefresh":false,
                                  "triggerType":"MANUAL"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.task.id").value(1))
                .andExpect(jsonPath("$.data.newlyCreated").value(true));
    }

    @Test
    void rejectsUnsupportedExecutionModeBeforeServiceCall() throws Exception {
        mockMvc.perform(post("/api/agent-tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "symbol":"600000",
                                  "tradeDate":"2026-07-14",
                                  "executionMode":"PAID_MODEL",
                                  "ruleVersion":"rules-1",
                                  "forceRefresh":false,
                                  "triggerType":"MANUAL"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void rejectsSixtyFiveCharacterRuleVersionAtControllerBoundary() throws Exception {
        String json = """
                {"symbol":"600000","tradeDate":"2026-07-14","executionMode":"LOCAL_RULES",
                 "ruleVersion":"%s","forceRefresh":false,"triggerType":"MANUAL"}
                """.formatted("v".repeat(65));

        mockMvc.perform(post("/api/agent-tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void returnsTaskById() throws Exception {
        when(service.task(1)).thenReturn(AgentTestFixtures.task(1, TaskStatus.QUEUED));
        mockMvc.perform(get("/api/agent-tasks/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1));
    }

    @Test
    void returns404ForMissingTask() throws Exception {
        when(service.task(404)).thenThrow(new AgentTaskNotFoundException(404));
        mockMvc.perform(get("/api/agent-tasks/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void illegalArgumentRemainsBadRequest() throws Exception {
        when(service.history(-1, 20)).thenThrow(new IllegalArgumentException("page不能小于0"));
        mockMvc.perform(get("/api/agent-tasks/history?page=-1&size=20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("page不能小于0"));
    }

    @Test
    void agentTeamExceptionReturnsSanitizedInternalServerError() throws Exception {
        when(service.task(500)).thenThrow(new AgentTeamException(
                "jdbc:postgresql://secret-host Python http://private:8001"
        ));
        mockMvc.perform(get("/api/agent-tasks/500"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("智能体任务处理失败"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secret-host")
                )))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("private:8001")
                )));
    }

    @Test
    void exposesRunsEvidenceAndDecisionQueries() throws Exception {
        when(service.runs(1)).thenReturn(List.of());
        when(service.evidence(1)).thenReturn(List.of());
        when(service.decision(1)).thenReturn(AgentTestFixtures.validResponse().finalDecision());

        mockMvc.perform(get("/api/agent-tasks/1/runs")).andExpect(status().isOk());
        mockMvc.perform(get("/api/agent-tasks/1/evidence")).andExpect(status().isOk());
        mockMvc.perform(get("/api/agent-tasks/1/decision"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decision").value("WATCH"));
    }

    @Test
    void historyUsesZeroBasedPageAndBoundedSize() throws Exception {
        when(service.history(0, 20)).thenReturn(new PageResult<>(List.of(), 0, 20, 0));
        mockMvc.perform(get("/api/agent-tasks/history?page=0&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20));
    }
}

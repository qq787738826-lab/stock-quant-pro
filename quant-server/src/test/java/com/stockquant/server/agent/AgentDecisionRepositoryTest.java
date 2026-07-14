package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.exception.AgentTeamException;
import com.stockquant.server.agent.model.AgentModels.FinalDecision;
import com.stockquant.server.agent.repository.AgentDecisionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentDecisionRepositoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void queryPreservesLogicalVetoIdFromDecisionJsonInsteadOfDatabaseId() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet resultSet = mock(ResultSet.class);
        java.sql.Array internalVetoIds = mock(java.sql.Array.class);
        FinalDecision expected = AgentTestFixtures.withVeto(
                AgentTestFixtures.validResponse(), true
        ).finalDecision();
        when(resultSet.getString("decision_json")).thenReturn(objectMapper.writeValueAsString(expected));
        when(resultSet.getArray("veto_ids")).thenReturn(internalVetoIds);
        when(internalVetoIds.getArray()).thenReturn(new Long[]{37L});
        stubSingleRow(jdbc, resultSet);

        FinalDecision actual = new AgentDecisionRepository(jdbc, objectMapper)
                .findByTaskId(1).orElseThrow();

        assertEquals(List.of("veto-1"), actual.vetoIds());
        verify(resultSet, never()).getArray("veto_ids");
        verify(resultSet, never()).getArray("source_run_ids");
    }

    @Test
    void emptyDecisionJsonBecomesExplicitServerException() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("decision_json")).thenReturn(null);
        stubSingleRow(jdbc, resultSet);

        AgentDecisionRepository repository = new AgentDecisionRepository(jdbc, objectMapper);
        assertThrows(AgentTeamException.class, () -> repository.findByTaskId(1));
    }

    @Test
    void damagedDecisionJsonBecomesExplicitServerException() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("decision_json")).thenReturn("{not-json}");
        stubSingleRow(jdbc, resultSet);

        AgentDecisionRepository repository = new AgentDecisionRepository(jdbc, objectMapper);
        assertThrows(AgentTeamException.class, () -> repository.findByTaskId(1));
    }

    @SuppressWarnings("unchecked")
    private static void stubSingleRow(JdbcTemplate jdbc, ResultSet resultSet) {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(1L))).thenAnswer(invocation -> {
            RowMapper<FinalDecision> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(resultSet, 0));
        });
    }
}

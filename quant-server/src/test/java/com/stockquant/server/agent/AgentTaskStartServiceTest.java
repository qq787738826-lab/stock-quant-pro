package com.stockquant.server.agent;

import com.stockquant.server.agent.repository.AgentRunRepository;
import com.stockquant.server.agent.repository.AgentTaskRepository;
import com.stockquant.server.agent.service.AgentTaskStartService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskStartServiceTest {

    @Test
    void queuedTaskAndSixRunsAreClaimedTogether() {
        AgentTaskRepository tasks = mock(AgentTaskRepository.class);
        AgentRunRepository runs = mock(AgentRunRepository.class);
        when(tasks.claimQueuedTask(1)).thenReturn(1);
        AgentTaskStartService service = new AgentTaskStartService(tasks, runs);

        assertTrue(service.claim(1));
        verify(runs).markAllRunning(1);
    }

    @Test
    void runningOrTerminalTaskReturnsFalseWithoutTouchingRuns() {
        AgentTaskRepository tasks = mock(AgentTaskRepository.class);
        AgentRunRepository runs = mock(AgentRunRepository.class);
        when(tasks.claimQueuedTask(2)).thenReturn(0);
        AgentTaskStartService service = new AgentTaskStartService(tasks, runs);

        assertFalse(service.claim(2));
        verify(runs, never()).markAllRunning(2);
    }

    @Test
    void runTransitionFailurePropagatesForTransactionRollback() {
        AgentTaskRepository tasks = mock(AgentTaskRepository.class);
        AgentRunRepository runs = mock(AgentRunRepository.class);
        when(tasks.claimQueuedTask(3)).thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("not six runs"))
                .when(runs).markAllRunning(3);
        AgentTaskStartService service = new AgentTaskStartService(tasks, runs);

        assertThrows(IllegalStateException.class, () -> service.claim(3));
    }

    @Test
    void claimMethodIsTransactional() throws Exception {
        Method method = AgentTaskStartService.class.getMethod("claim", long.class);
        Transactional annotation = method.getAnnotation(Transactional.class);
        assertEquals(org.springframework.transaction.annotation.Propagation.REQUIRED, annotation.propagation());
    }
}

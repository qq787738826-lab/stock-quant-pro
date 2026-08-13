package com.stockquant.server.production;

import com.stockquant.server.agent.evaluation.AgentEvaluationRepository;
import com.stockquant.server.agent.evaluation.AgentEvaluationService;
import com.stockquant.server.agent.shadowresearch.ShadowResearchRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** Guards production beans that Spring must subclass for persistence/AOP advice. */
class ProductionSpringProxyCompatibilityTest {

    @Test
    void advisedProductionBeansRemainSubclassable() {
        assertSubclassable(AgentEvaluationRepository.class);
        assertSubclassable(AgentEvaluationService.class);
        assertSubclassable(ShadowResearchRepository.class);
        assertSubclassable(LocalResearchBackupService.class);
    }

    private static void assertSubclassable(Class<?> type) {
        assertFalse(Modifier.isFinal(type.getModifiers()),
                () -> type.getName() + " must remain non-final for Spring AOP");
    }
}

package com.stockquant.server.production;

import com.stockquant.server.agent.evaluation.AgentEvaluationRepository;
import com.stockquant.server.agent.evaluation.AgentEvaluationService;
import com.stockquant.server.agent.shadowresearch.ShadowResearchRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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

    @Test
    void multiConstructorSpringBeansSelectTheirProductionConstructor() {
        assertAutowiredConstructor(
                com.stockquant.server.agent.research
                        .AgentResearchReportService.class);
        assertAutowiredConstructor(LocalResearchBackupService.class);
        assertAutowiredConstructor(ProductionLifecycleController.class);
        assertAutowiredConstructor(SystemHealthService.class);
    }

    private static void assertSubclassable(Class<?> type) {
        assertFalse(Modifier.isFinal(type.getModifiers()),
                () -> type.getName() + " must remain non-final for Spring AOP");
    }

    private static void assertAutowiredConstructor(Class<?> type) {
        long annotated = java.util.Arrays.stream(type.getDeclaredConstructors())
                .filter(constructor -> constructor
                        .isAnnotationPresent(Autowired.class))
                .count();
        assertFalse(annotated != 1,
                () -> type.getName()
                        + " must have exactly one @Autowired constructor");
    }
}

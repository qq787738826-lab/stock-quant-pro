package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.ProhibitedStageAttestation;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

/** Static reachability proof; it accepts no caller-provided stage booleans. */
final class TushareControlledAcceptanceBoundaryAttestor {
    private static final Set<String> FORBIDDEN_DEPENDENCY_NAMES = Set.of(
            "Scheduler", "Agent", "Backtest", "Shadow", "Trading",
            "Portfolio", "Broker", "Day002", "F2B", "F3");

    private TushareControlledAcceptanceBoundaryAttestor() {
    }

    static ProhibitedStageAttestation attest(Class<?> executorType) {
        if (executorType.isAnnotationPresent(Controller.class)
                || executorType.isAnnotationPresent(RestController.class)
                || CommandLineRunner.class.isAssignableFrom(executorType)
                || ApplicationRunner.class.isAssignableFrom(executorType)
                || Arrays.stream(executorType.getDeclaredMethods())
                .map(Method::getAnnotations)
                .flatMap(Arrays::stream)
                .anyMatch(annotation -> annotation.annotationType() == Scheduled.class)
                || Arrays.stream(executorType.getDeclaredConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes()))
                .map(Class::getSimpleName)
                .anyMatch(name -> FORBIDDEN_DEPENDENCY_NAMES.stream().anyMatch(name::contains))) {
            return ProhibitedStageAttestation.NOT_ATTESTED;
        }
        return ProhibitedStageAttestation.VERIFIED_UNREACHABLE;
    }
}

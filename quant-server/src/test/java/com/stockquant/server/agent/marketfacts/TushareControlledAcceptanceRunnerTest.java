package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceBuildProof.VerifiedBuildProof;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceExecution.Decision;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceOutputAudit.AuditResult;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceSecretChannel.SecretValue;
import org.junit.jupiter.api.Test;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TushareControlledAcceptanceRunnerTest {

    @Test
    void dedicatedRunnerInstallsAuditBeforeSecretsAndDatabaseAndClosesResources() {
        FakeEnvironment environment = new FakeEnvironment(false);

        int exit = TushareControlledAcceptanceRunner.run(
                new String[]{"--authorization-file=fake.properties"}, environment);

        assertEquals(TushareControlledAcceptanceRunner.EXIT_SUCCESS, exit);
        assertTrue(environment.auditInstalledAtBuildProof);
        assertTrue(environment.auditInstalledAtDatabaseSecret);
        assertTrue(environment.auditInstalledAtDataSource);
        assertEquals(List.of(
                "build-proof", "launch-plan", "secret-channel",
                "database-secret", "open-database", "governance",
                "secret-channel", "tushare-token", "execute",
                "complete", "execution-close", "database-close"),
                environment.events);
        assertTrue(environment.databaseClosed);
        assertTrue(environment.executionClosed);
    }

    @Test
    void auditedSecretOutputRejectsPassAndStillClosesResources() {
        FakeEnvironment environment = new FakeEnvironment(true);

        int exit = TushareControlledAcceptanceRunner.run(
                new String[]{"--authorization-file=fake.properties"}, environment);

        assertEquals(TushareControlledAcceptanceRunner.EXIT_REJECTED, exit);
        assertFalse(environment.completed);
        assertTrue(environment.databaseClosed);
        assertTrue(environment.executionClosed);
    }

    @Test
    void missingBuildProofFailsBeforeSecretOrDatabaseInitialization() {
        FakeEnvironment environment = new FakeEnvironment(false) {
            @Override
            public VerifiedBuildProof loadBuildProof() {
                events.add("build-proof");
                throw new IllegalStateException(
                        "TUSHARE_CONTROLLED_ACCEPTANCE_BUILD_PROOF_MISSING");
            }
        };

        int exit = TushareControlledAcceptanceRunner.run(new String[0], environment);

        assertEquals(TushareControlledAcceptanceRunner.EXIT_REJECTED, exit);
        assertEquals(List.of("build-proof"), environment.events);
        assertFalse(environment.databaseClosed);
    }

    @Test
    void missingSecureSecretChannelFailsClosedBeforeDatabase() {
        FakeEnvironment environment = new FakeEnvironment(false) {
            @Override
            public TushareControlledAcceptanceSecretChannel secretChannel() {
                events.add("secret-channel");
                throw new IllegalStateException(
                        "TUSHARE_CONTROLLED_ACCEPTANCE_SECURE_CONSOLE_REQUIRED");
            }
        };

        int exit = TushareControlledAcceptanceRunner.run(
                new String[]{"--authorization-file=fake.properties"}, environment);

        assertEquals(TushareControlledAcceptanceRunner.EXIT_REJECTED, exit);
        assertEquals(List.of("build-proof", "launch-plan", "secret-channel"),
                environment.events);
        assertFalse(environment.databaseClosed);
    }

    @Test
    void newlyCreatedNonDaemonThreadRejectsSuccessAndIsClosed() {
        FakeEnvironment environment = new FakeEnvironment(false) {
            @Override
            public TushareControlledAcceptanceRunner.ExecutionHandle execute(
                    TushareControlledAcceptanceRunner.RuntimeDatabase ignored,
                    TushareControlledAcceptanceLaunchPlan ignoredPlan,
                    TushareControlledAcceptanceAuthorization ignoredAuthorization,
                    VerifiedBuildProof ignoredProof,
                    char[] token
            ) {
                events.add("execute");
                Thread worker = new Thread(() -> {
                    try {
                        Thread.sleep(30_000);
                    } catch (InterruptedException expected) {
                        Thread.currentThread().interrupt();
                    }
                }, "f1f-b2-test-nondaemon");
                worker.setDaemon(false);
                worker.start();
                return new TushareControlledAcceptanceRunner.ExecutionHandle() {
                    @Override
                    public Decision complete(AuditResult audit) {
                        completed = true;
                        return Decision.internalPassed();
                    }

                    @Override
                    public void close() {
                        worker.interrupt();
                        try {
                            worker.join(2_000);
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                        }
                        executionClosed = true;
                        openedDatabase.close();
                    }
                };
            }
        };

        int exit = TushareControlledAcceptanceRunner.run(
                new String[]{"--authorization-file=fake.properties"}, environment);

        assertEquals(TushareControlledAcceptanceRunner.EXIT_REJECTED, exit);
        assertFalse(environment.completed);
        assertTrue(environment.executionClosed);
        assertTrue(environment.databaseClosed);
        assertTrue(Thread.getAllStackTraces().keySet().stream()
                .noneMatch(thread -> thread.isAlive()
                        && "f1f-b2-test-nondaemon".equals(thread.getName())));
    }

    @Test
    void guardedGovernanceBootstrapNeverCreatesOperationsWhenVerificationFails() {
        AtomicInteger operationFactories = new AtomicInteger();
        AtomicInteger ddlOperations = new AtomicInteger();

        assertThrows(IllegalStateException.class, () ->
                TushareControlledAcceptanceDatabaseGuard
                        .performGuardedGovernanceInitialization(
                                () -> {
                                    throw new IllegalStateException(
                                            "TUSHARE_CONTROLLED_ACCEPTANCE_PUBLIC_SCHEMA_FORBIDDEN");
                                },
                                ignored -> {
                                    operationFactories.incrementAndGet();
                                    return operations(ddlOperations, new ArrayList<>());
                                }));

        assertEquals(0, operationFactories.get());
        assertEquals(0, ddlOperations.get());
    }

    @Test
    void explicitGovernanceBootstrapOrdersBaselineThenMigrationWithoutAutoBaseline() {
        var verification = mock(
                TushareDedicatedResearchPersistenceGuard.Verification.class);
        List<String> calls = new ArrayList<>();
        AtomicInteger operations = new AtomicInteger();

        TushareControlledAcceptanceDatabaseGuard.performGuardedGovernanceInitialization(
                () -> new TushareControlledAcceptanceDatabaseGuard.PreMigrationVerification(
                        verification,
                        TushareControlledAcceptanceDatabaseGuard.GovernanceState.ABSENT),
                ignored -> operations(operations, calls));

        assertEquals(List.of("baseline", "migrate"), calls);
        assertEquals(2, operations.get());
        assertTrue(readSource("src/main/java/com/stockquant/server/agent/marketfacts/"
                + "TushareControlledAcceptanceDatabaseGuard.java")
                .contains(".baselineOnMigrate(false)"));
        assertFalse(readSource("src/main/java/com/stockquant/server/agent/marketfacts/"
                + "TushareControlledAcceptanceDatabaseGuard.java")
                .contains(".baselineOnMigrate(true)"));
    }

    @Test
    void launcherAndBuildScriptsFreezeTheDedicatedOfflineBoundary() {
        String runner = readSource("src/main/java/com/stockquant/server/agent/marketfacts/"
                + "TushareControlledAcceptanceRunner.java");
        String build = readSource("scripts/prepare-f1f-b2-build-proof.ps1");
        String launch = readSource("scripts/run-f1f-b2-controlled-acceptance.ps1");

        assertFalse(runner.contains("SpringApplication"));
        assertFalse(runner.contains("QuantServerApplication"));
        assertFalse(runner.contains("@Scheduled"));
        assertFalse(runner.contains("@Controller"));
        assertEquals(
                TushareControlledAcceptanceExecution.ProhibitedStageAttestation
                        .VERIFIED_UNREACHABLE,
                TushareControlledAcceptanceBoundaryAttestor.attest(
                        TushareControlledAcceptanceRunner.class));
        assertTrue(build.contains("[string] $Mode = 'PREPARATION_ONLY'"));
        assertTrue(build.contains("CONTROLLED_BUILD_ARTIFACT"));
        assertTrue(build.contains("mvnw.cmd"));
        assertFalse(build.matches("(?s).*&\\s+mvn\\s+.*"));
        assertTrue(build.contains("Stock-Quant-Maven-Wrapper-Version"));
        assertTrue(build.contains("Stock-Quant-Git-Remote-Commit"));
        assertTrue(launch.contains("TushareControlledAcceptanceRunner"));
        assertFalse(launch.contains("QuantServerApplication"));
    }

    private static TushareControlledAcceptanceDatabaseGuard.GovernanceOperations
    operations(AtomicInteger count, List<String> calls) {
        return new TushareControlledAcceptanceDatabaseGuard.GovernanceOperations() {
            @Override
            public void baseline() {
                count.incrementAndGet();
                calls.add("baseline");
            }

            @Override
            public void migrate() {
                count.incrementAndGet();
                calls.add("migrate");
            }
        };
    }

    private static String readSource(String relative) {
        try {
            return Files.readString(Path.of(relative));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static class FakeEnvironment
            implements TushareControlledAcceptanceRunner.RunnerEnvironment {
        final List<String> events = new ArrayList<>();
        final PrintStream originalOut = System.out;
        final VerifiedBuildProof proof = mock(VerifiedBuildProof.class);
        final TushareControlledAcceptanceLaunchPlan plan =
                mock(TushareControlledAcceptanceLaunchPlan.class);
        final TushareControlledAcceptanceAuthorization authorization =
                mock(TushareControlledAcceptanceAuthorization.class);
        final boolean leakToken;
        boolean auditInstalledAtBuildProof;
        boolean auditInstalledAtDatabaseSecret;
        boolean auditInstalledAtDataSource;
        boolean completed;
        boolean executionClosed;
        boolean databaseClosed;
        TushareControlledAcceptanceRunner.RuntimeDatabase openedDatabase;

        FakeEnvironment(boolean leakToken) {
            this.leakToken = leakToken;
            when(plan.authorization(proof)).thenReturn(authorization);
        }

        @Override
        public VerifiedBuildProof loadBuildProof() {
            events.add("build-proof");
            auditInstalledAtBuildProof = System.out != originalOut;
            return proof;
        }

        @Override
        public TushareControlledAcceptanceLaunchPlan loadPlan(String[] args) {
            events.add("launch-plan");
            return plan;
        }

        @Override
        public TushareControlledAcceptanceSecretChannel secretChannel() {
            events.add("secret-channel");
            return new TushareControlledAcceptanceSecretChannel() {
                @Override
                public SecretValue readDatabasePassword() {
                    events.add("database-secret");
                    auditInstalledAtDatabaseSecret = System.out != originalOut;
                    return new SecretValue("fake-database-password".toCharArray());
                }

                @Override
                public SecretValue readTushareToken() {
                    events.add("tushare-token");
                    return new SecretValue("fake-tushare-token-value".toCharArray());
                }
            };
        }

        @Override
        public TushareControlledAcceptanceRunner.RuntimeDatabase openDatabase(
                TushareControlledAcceptanceLaunchPlan ignored,
                char[] password
        ) {
            events.add("open-database");
            auditInstalledAtDataSource = System.out != originalOut;
            assertNotEquals(0, password[0]);
            openedDatabase = new TushareControlledAcceptanceRunner.RuntimeDatabase() {
                @Override
                public javax.sql.DataSource dataSource() {
                    return mock(javax.sql.DataSource.class);
                }

                @Override
                public void close() {
                    events.add("database-close");
                    databaseClosed = true;
                }
            };
            return openedDatabase;
        }

        @Override
        public void initializeGovernance(
                TushareControlledAcceptanceRunner.RuntimeDatabase ignored,
                TushareControlledAcceptanceAuthorization ignoredAuthorization,
                VerifiedBuildProof ignoredProof
        ) {
            events.add("governance");
        }

        @Override
        public TushareControlledAcceptanceRunner.ExecutionHandle execute(
                TushareControlledAcceptanceRunner.RuntimeDatabase ignored,
                TushareControlledAcceptanceLaunchPlan ignoredPlan,
                TushareControlledAcceptanceAuthorization ignoredAuthorization,
                VerifiedBuildProof ignoredProof,
                char[] token
        ) {
            events.add("execute");
            if (leakToken) {
                System.out.print(new String(token));
            }
            return new TushareControlledAcceptanceRunner.ExecutionHandle() {
                @Override
                public Decision complete(AuditResult audit) {
                    events.add("complete");
                    completed = true;
                    assertTrue(audit.clean());
                    return Decision.internalPassed();
                }

                @Override
                public void close() {
                    events.add("execution-close");
                    executionClosed = true;
                    openedDatabase.close();
                }
            };
        }
    }
}

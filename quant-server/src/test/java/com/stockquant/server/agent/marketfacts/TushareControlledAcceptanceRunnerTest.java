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
import java.util.Set;
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
        assertTrue(environment.auditInstalledAtPreAuditClose);
        assertEquals(List.of(
                "launch-plan", "build-proof", "secret-channel",
                "database-secret", "open-database", "governance",
                "secret-channel", "tushare-token", "execute",
                "execution-pre-audit-close", "complete", "execution-close",
                "database-close"),
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
        assertTrue(environment.completed,
                "dirty audit is presented to the terminal-state writer");
        assertTrue(environment.databaseClosed);
        assertTrue(environment.executionClosed);
    }

    @Test
    void componentCloseOutputIsIncludedInFinalAuditBeforePass() {
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
                return new TushareControlledAcceptanceRunner.ExecutionHandle() {
                    @Override
                    public void closeBeforeFinalAudit() {
                        events.add("execution-pre-audit-close");
                        System.out.print("fake-tushare-token-value");
                    }

                    @Override
                    public Decision complete(AuditResult audit) {
                        completed = true;
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
        };

        int exit = TushareControlledAcceptanceRunner.run(
                new String[]{"--authorization-file=fake.properties"}, environment);

        assertEquals(TushareControlledAcceptanceRunner.EXIT_REJECTED, exit);
        assertTrue(environment.completed,
                "the runner cannot trust a handle that ignores a dirty audit");
        assertTrue(environment.executionClosed);
        assertTrue(environment.databaseClosed);
    }

    @Test
    void missingBuildProofFailsBeforeSecretOrDatabaseInitialization() {
        FakeEnvironment environment = new FakeEnvironment(false) {
            @Override
            public VerifiedBuildProof loadBuildProof(
                    TushareControlledAcceptanceLaunchPlan ignoredPlan
            ) {
                events.add("build-proof");
                throw new IllegalStateException(
                        "TUSHARE_CONTROLLED_ACCEPTANCE_BUILD_PROOF_MISSING");
            }
        };

        int exit = TushareControlledAcceptanceRunner.run(new String[0], environment);

        assertEquals(TushareControlledAcceptanceRunner.EXIT_REJECTED, exit);
        assertEquals(List.of("launch-plan", "build-proof"), environment.events);
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
        assertEquals(List.of("launch-plan", "build-proof", "secret-channel"),
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
                    public void closeBeforeFinalAudit() {
                        // Deliberately leave the worker alive so the runner's
                        // post-component-close thread guard must reject it.
                    }

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
    void firstTerminalWriteFailureInvokesRecoveryBeforeProcessExit() {
        AtomicInteger recoveryCalls = new AtomicInteger();
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
                return new TushareControlledAcceptanceRunner.ExecutionHandle() {
                    @Override
                    public void closeBeforeFinalAudit() {
                        events.add("execution-pre-audit-close");
                    }

                    @Override
                    public Decision complete(AuditResult audit) {
                        throw new IllegalStateException(
                                "TUSHARE_FINAL_STATE_WRITE_FAILED");
                    }

                    @Override
                    public void fail(Throwable error) {
                        recoveryCalls.incrementAndGet();
                    }

                    @Override
                    public void close() {
                        executionClosed = true;
                        openedDatabase.close();
                    }
                };
            }
        };

        int exit = TushareControlledAcceptanceRunner.run(
                new String[]{"--authorization-file=fake.properties"}, environment);

        assertEquals(TushareControlledAcceptanceRunner.EXIT_REJECTED, exit);
        assertEquals(1, recoveryCalls.get());
        assertTrue(environment.executionClosed);
        assertTrue(environment.databaseClosed);
    }

    @Test
    void packagedE2eModeUsesNoRealSecretChannelAndCannotProjectOperationalPass() {
        FakeEnvironment environment = new FakeEnvironment(false);
        when(environment.plan.e2eDryRun()).thenReturn(true);

        int exit = TushareControlledAcceptanceRunner.run(
                new String[]{"--authorization-file=fake.properties"}, environment);

        assertEquals(TushareControlledAcceptanceRunner.EXIT_SUCCESS, exit);
        assertFalse(environment.events.contains("secret-channel"));
        assertFalse(environment.events.contains("database-secret"));
        assertFalse(environment.events.contains("tushare-token"));
        assertTrue(environment.databaseClosed);
        assertTrue(environment.executionClosed);
    }

    @Test
    void failureAfterExecutionCreationIsFinalizedBeforeResourcesClose() {
        AtomicInteger recoveryCalls = new AtomicInteger();
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
                return new TushareControlledAcceptanceRunner.ExecutionHandle() {
                    @Override
                    public void closeBeforeFinalAudit() {
                        throw new AssertionError("TUSHARE_PRE_AUDIT_CLOSE_FAILED");
                    }

                    @Override
                    public Decision complete(AuditResult audit) {
                        return Decision.internalPassed();
                    }

                    @Override
                    public void fail(Throwable error) {
                        recoveryCalls.incrementAndGet();
                    }

                    @Override
                    public void close() {
                        executionClosed = true;
                        openedDatabase.close();
                    }
                };
            }
        };

        int exit = TushareControlledAcceptanceRunner.run(
                new String[]{"--authorization-file=fake.properties"}, environment);

        assertEquals(TushareControlledAcceptanceRunner.EXIT_REJECTED, exit);
        assertEquals(1, recoveryCalls.get());
        assertTrue(environment.executionClosed);
        assertTrue(environment.databaseClosed);
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
    void failedExplicitGovernanceBaselineNeverRunsMigration() {
        var verification = mock(
                TushareDedicatedResearchPersistenceGuard.Verification.class);
        AtomicInteger migrations = new AtomicInteger();

        assertThrows(IllegalStateException.class, () ->
                TushareControlledAcceptanceDatabaseGuard
                        .performGuardedGovernanceInitialization(
                                () -> new TushareControlledAcceptanceDatabaseGuard
                                        .PreMigrationVerification(
                                        verification,
                                        TushareControlledAcceptanceDatabaseGuard
                                                .GovernanceState.ABSENT),
                                ignored -> new TushareControlledAcceptanceDatabaseGuard
                                        .GovernanceOperations() {
                                    @Override
                                    public void baseline() {
                                        throw new IllegalStateException(
                                                "TEST_BASELINE_FAILED");
                                    }

                                    @Override
                                    public void migrate() {
                                        migrations.incrementAndGet();
                                    }
                                }));

        assertEquals(0, migrations.get());
    }

    @Test
    void launcherAndBuildScriptsFreezeTheDedicatedOfflineBoundary() {
        String runner = readSource("src/main/java/com/stockquant/server/agent/marketfacts/"
                + "TushareControlledAcceptanceRunner.java");
        String build = readSource("scripts/prepare-f1f-b2-build-proof.ps1");
        String launch = readSource("scripts/run-f1f-b2-controlled-acceptance.ps1");
        String components = readSource(
                "src/main/java/com/stockquant/server/agent/marketfacts/"
                        + "TushareControlledAcceptanceComponents.java");

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
        assertTrue(build.contains("git archive --format=zip"));
        assertTrue(build.contains("-o -pl quant-server -am package"));
        assertFalse(build.contains("-am clean package"));
        assertTrue(build.contains("quant-server-1.3.1-f1f-b2-runner.jar"));
        assertTrue(build.contains("$ErrorActionPreference = 'Continue'"));
        assertTrue(build.contains("$javaVersionExitCode = $LASTEXITCODE"));
        assertTrue(build.contains("-XshowSettings:properties"));
        assertTrue(build.contains("TUSHARE_CONTROLLED_ACCEPTANCE_JAVA_VERSION_UNAVAILABLE"));
        assertTrue(build.contains(
                "TUSHARE_CONTROLLED_ACCEPTANCE_BUILD_JAVA_VERSION_MISMATCH"));
        assertTrue(build.contains("Stock-Quant-Maven-Wrapper-Version"));
        assertTrue(build.contains("Stock-Quant-Git-Remote-Commit"));
        assertTrue(build.contains(
                "Main-Class: org.springframework.boot.loader.launch.JarLauncher"));
        assertTrue(build.contains(
                "Start-Class: com.stockquant.server.agent.marketfacts."
                        + "TushareControlledAcceptanceRunner"));
        assertFalse(launch.contains("QuantServerApplication"));
        assertTrue(launch.contains("& java -jar $artifact"));
        assertFalse(launch.contains("loader.main"));
        assertFalse(build.contains("PropertiesLauncher"));
        assertTrue(components.contains("registerModule(new JavaTimeModule())"));
        assertFalse(components.contains("findAndRegisterModules"));
        assertFalse(components.contains("ServiceLoader"));
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
        boolean auditInstalledAtPreAuditClose;
        boolean completed;
        boolean executionClosed;
        boolean databaseClosed;
        TushareControlledAcceptanceRunner.RuntimeDatabase openedDatabase;

        FakeEnvironment(boolean leakToken) {
            this.leakToken = leakToken;
            when(plan.authorization(proof)).thenReturn(authorization);
        }

        @Override
        public VerifiedBuildProof loadBuildProof(
                TushareControlledAcceptanceLaunchPlan ignoredPlan
        ) {
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
        public TushareControlledAcceptanceRunner.RuntimeDatabase
        openE2eDryRunDatabase(TushareControlledAcceptanceLaunchPlan ignored) {
            events.add("open-e2e-database");
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
                public void closeBeforeFinalAudit() {
                    events.add("execution-pre-audit-close");
                    auditInstalledAtPreAuditClose = System.out != originalOut;
                }

                @Override
                public Decision complete(AuditResult audit) {
                    events.add("complete");
                    completed = true;
                    assertSame(originalOut, System.out,
                            "PASSED persistence occurs only after the final audit");
                    assertTrue(audit.clean());
                    return plan.e2eDryRun()
                            ? Decision.testCandidate(Set.of(
                            "REAL_CONTROLLED_ACCEPTANCE_NOT_RUN"))
                            : Decision.internalPassed();
                }

                @Override
                public boolean successfulExit(Decision decision) {
                    return plan.e2eDryRun()
                            ? decision.status()
                            == TushareControlledAcceptanceExecution
                            .ExecutionStatus.SUCCEEDED_CANDIDATE
                            && !decision.reducedResearchOperationalReady()
                            : TushareControlledAcceptanceRunner.ExecutionHandle
                            .super.successfulExit(decision);
                }

                @Override
                public void close() {
                    events.add("execution-close");
                    executionClosed = true;
                    openedDatabase.close();
                }
            };
        }

        @Override
        public TushareControlledAcceptanceRunner.ExecutionHandle executeE2eDryRun(
                TushareControlledAcceptanceRunner.RuntimeDatabase database,
                TushareControlledAcceptanceLaunchPlan plan,
                TushareControlledAcceptanceAuthorization authorization,
                VerifiedBuildProof proof
        ) {
            return execute(database, plan, authorization, proof,
                    "E2E_DRY_RUN_FAKE_TOKEN".toCharArray());
        }
    }
}

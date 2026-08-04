package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceDatabasePreparationPlan.ExecutionScope;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceDatabasePreparationPlan.Mode;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceDatabasePreparationService.Phase;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceDatabasePreparationService.PreparationReport;
import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceSecretChannel.SecretValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class TushareControlledAcceptanceDatabasePreparerTest {
    private static final String COMMIT = "a".repeat(40);

    @Test
    void defaultModeIsPreparationOnlyAndPortIsExplicit() {
        var plan = TushareControlledAcceptanceDatabasePreparationPlan.parse(
                new String[]{"--expected-commit=" + COMMIT,
                        "--database-port=25432", "--admin-user=postgres"});

        assertEquals(Mode.PREPARATION_ONLY, plan.mode());
        assertFalse(plan.databaseExecutionAllowed());
        assertEquals(25432, plan.databasePort());
    }

    @Test
    void missingPortAndUnknownLooseOverridesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> TushareControlledAcceptanceDatabasePreparationPlan.parse(
                        new String[]{"--expected-commit=" + COMMIT,
                                "--admin-user=postgres"}));
        assertThrows(IllegalArgumentException.class,
                () -> TushareControlledAcceptanceDatabasePreparationPlan.parse(
                        new String[]{"--expected-commit=" + COMMIT,
                                "--database-port=25432", "--admin-user=postgres",
                                "--database-host=127.0.0.1"}));
    }

    @Test
    void duplicateArgumentsAndSecretArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> TushareControlledAcceptanceDatabasePreparationPlan.parse(
                        new String[]{"--expected-commit=" + COMMIT,
                                "--database-port=25432", "--database-port=25433",
                                "--admin-user=postgres"}));
        assertThrows(IllegalArgumentException.class,
                () -> TushareControlledAcceptanceDatabasePreparationPlan.parse(
                        new String[]{"--expected-commit=" + COMMIT,
                                "--database-port=25432", "--admin-user=postgres",
                                "--password=fake-password"}));
    }

    @Test
    void formalModeRequiresExplicitUserApproval() {
        assertThrows(IllegalArgumentException.class,
                () -> TushareControlledAcceptanceDatabasePreparationPlan.parse(
                        new String[]{"--mode=CONTROLLED_DATABASE_PREPARATION",
                                "--expected-commit=" + COMMIT,
                                "--database-port=25432", "--admin-user=postgres"}));

        var plan = TushareControlledAcceptanceDatabasePreparationPlan.parse(
                new String[]{"--mode=CONTROLLED_DATABASE_PREPARATION",
                        "--expected-commit=" + COMMIT,
                        "--database-port=25432", "--admin-user=postgres",
                        "--user-approval-reference=USER_APPROVED_DBPREP"});
        assertTrue(plan.databaseExecutionAllowed());
        assertTrue(plan.formalExecution());
    }

    @Test
    void fixedTargetContractCannotBeOverridden() {
        assertAll(
                () -> assertEquals("127.0.0.1",
                        TushareControlledAcceptanceDatabasePreparationPlan.HOST),
                () -> assertEquals("stock_quant_research",
                        TushareControlledAcceptanceDatabasePreparationPlan.DATABASE),
                () -> assertEquals("stock_quant_research",
                        TushareControlledAcceptanceDatabasePreparationPlan.USER),
                () -> assertEquals("tushare_research",
                        TushareControlledAcceptanceDatabasePreparationPlan.SCHEMA),
                () -> assertEquals(13,
                        TushareControlledAcceptanceDatabasePreparationPlan.MAIN_VERSION));
    }

    @Test
    void preparationOnlyDoesNotReadSecretsOrOpenDatabase() {
        FakeEnvironment environment = new FakeEnvironment(preparationPlan());

        int exit = TushareControlledAcceptanceDatabasePreparer.run(
                new String[0], environment);

        assertEquals(TushareControlledAcceptanceDatabasePreparer.EXIT_SUCCESS, exit);
        assertEquals(List.of("plan", "validate-only"), environment.events);
    }

    @Test
    void outputAuditPrecedesSecretsAndDatabaseAndClearsSecretValues() {
        FakeEnvironment environment = new FakeEnvironment(formalPlan());

        int exit = TushareControlledAcceptanceDatabasePreparer.run(
                new String[0], environment);

        assertEquals(TushareControlledAcceptanceDatabasePreparer.EXIT_SUCCESS, exit);
        assertEquals(List.of("plan", "secret-channel", "admin-secret",
                "prepare-admin", "bootstrap-secret", "secret-channel",
                "dedicated-secret", "prepare"), environment.events);
        assertTrue(environment.auditAtPlan);
        assertTrue(environment.auditAtAdminSecret);
        assertTrue(environment.auditAtPrepare);
        assertTrue(environment.adminValue.cleared());
        assertTrue(environment.dedicatedValue.cleared());
        assertTrue(environment.copiesClearedAfterReturn());
    }

    @Test
    void missingSecureConsoleFailsBeforeDatabasePreparation() {
        FakeEnvironment environment = new FakeEnvironment(formalPlan()) {
            @Override
            public TushareControlledAcceptanceSecretChannel secretChannel() {
                events.add("secret-channel");
                throw new IllegalStateException(
                        "TUSHARE_CONTROLLED_ACCEPTANCE_SECURE_CONSOLE_REQUIRED");
            }
        };

        int exit = TushareControlledAcceptanceDatabasePreparer.run(
                new String[0], environment);

        assertEquals(TushareControlledAcceptanceDatabasePreparer.EXIT_REJECTED, exit);
        assertEquals(List.of("plan", "secret-channel"), environment.events);
    }

    @Test
    void secretPrintedInsidePreparationIsRejectedByAudit() {
        FakeEnvironment environment = new FakeEnvironment(formalPlan()) {
            @Override
            public PreparationReport prepare(
                    TushareControlledAcceptanceDatabasePreparationPlan plan,
                    char[] administratorPassword,
                    TushareControlledAcceptanceDatabasePreparationService
                            .DedicatedPasswordSupplier dedicatedPasswordSupplier,
                    TushareControlledAcceptanceDatabasePreparationService
                            .BootstrapSecretRegistrar bootstrapSecretRegistrar
            ) {
                System.out.print(new String(administratorPassword));
                return super.prepare(plan, administratorPassword,
                        dedicatedPasswordSupplier, bootstrapSecretRegistrar);
            }
        };

        assertEquals(TushareControlledAcceptanceDatabasePreparer.EXIT_REJECTED,
                TushareControlledAcceptanceDatabasePreparer.run(
                        new String[0], environment));
    }

    @Test
    void preparationDataSourceRedactsAndClearsPassword() {
        char[] password = "dedicated-secret-value".toCharArray();
        var source = new TushareControlledAcceptanceDatabasePreparationDataSource(
                25432, "stock_quant_research", "stock_quant_research",
                "tushare_research", password);

        assertEquals("TushareControlledAcceptanceDatabasePreparationDataSource[REDACTED]",
                source.toString());
        source.close();
        assertTrue(source.closed());
        assertThrows(Exception.class, source::getConnection);
    }

    @Test
    void mainFlywayUsesOnlyV1ThroughV13WithoutBaselineOrClean() {
        var contract = TushareControlledAcceptanceDatabasePreparationService
                .MainFlywayContract.frozen();

        assertFalse(contract.baselineOnMigrate());
        assertTrue(contract.cleanDisabled());
        assertFalse(contract.outOfOrder());
        assertFalse(contract.repairExposed());
        assertEquals("13", contract.targetVersion());
        assertEquals("classpath:db/migration", contract.location());
        assertEquals("flyway_schema_history", contract.historyTable());
    }

    @Test
    void preparerIsStandaloneAndProductionWhitelistHasNoProvider() {
        assertEquals(0, TushareControlledAcceptanceDatabasePreparer.class
                .getAnnotations().length);
        assertTrue(java.util.Arrays.stream(
                        TushareControlledAcceptanceDatabasePreparer.ProductionEnvironment.class
                                .getDeclaredFields())
                .noneMatch(field -> field.getType().getSimpleName().contains("Provider")));
    }

    @Test
    void authorizationRequiresHostAndBuildProofPath() {
        Properties properties = authorizationProperties();
        var plan = TushareControlledAcceptanceLaunchPlan.from(properties);
        assertEquals(Path.of(properties.getProperty("build.proof.path"))
                .toAbsolutePath().normalize(), plan.buildProofPath());

        properties.remove("database.host");
        assertThrows(IllegalArgumentException.class,
                () -> TushareControlledAcceptanceLaunchPlan.from(properties));
    }

    @Test
    void e2eDryRunAuthorizationIsTypedAndCannotMasqueradeAsUserApproval() {
        Properties properties = authorizationProperties();
        properties.setProperty("authorization.status", "E2E_DRY_RUN");
        properties.setProperty("purpose", "F1F_B2_E2E_DRY_RUN");
        properties.setProperty("execution.source", "TEST");
        properties.setProperty("user.approval.reference",
                "NOT_APPLICABLE_E2E_DRY_RUN");

        var plan = TushareControlledAcceptanceLaunchPlan.from(properties);

        assertTrue(plan.e2eDryRun());
        assertEquals(TushareControlledAcceptanceExecution.ExecutionSource.TEST,
                plan.executionSource());
        properties.setProperty("execution.source", "REAL_CONTROLLED_ACCEPTANCE");
        assertThrows(IllegalArgumentException.class,
                () -> TushareControlledAcceptanceLaunchPlan.from(properties));
    }

    @Test
    void strictAuthorizationFileRejectsDuplicateAndUnknownFields(@TempDir Path temp)
            throws Exception {
        Properties properties = authorizationProperties();
        List<String> lines = new ArrayList<>();
        properties.stringPropertyNames().stream().sorted()
                .forEach(key -> lines.add(key + '=' + properties.getProperty(key)));
        lines.add("database.port=25433");
        Path duplicate = temp.resolve("duplicate.properties");
        Files.write(duplicate, lines, StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> TushareControlledAcceptanceLaunchPlan.load(duplicate));

        lines.remove(lines.size() - 1);
        lines.add("database.override=forbidden");
        Path unknown = temp.resolve("unknown.properties");
        Files.write(unknown, lines, StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> TushareControlledAcceptanceLaunchPlan.load(unknown));
    }

    @Test
    void obsoleteDraftAcceptanceIdCannotBeAuthorized() {
        Properties properties = authorizationProperties();
        properties.setProperty("acceptance.id",
                TushareControlledAcceptanceLaunchPlan.OBSOLETE_DRAFT_ACCEPTANCE_ID);
        assertThrows(IllegalArgumentException.class,
                () -> TushareControlledAcceptanceLaunchPlan.from(properties));
    }

    @Test
    void authorizationRejectsSecretLikeFields() {
        Properties properties = authorizationProperties();
        properties.setProperty("database.password", "not-a-real-secret");
        assertThrows(IllegalArgumentException.class,
                () -> TushareControlledAcceptanceLaunchPlan.from(properties));
    }

    @Test
    void planRejectsReservedAdministratorInvalidCommitAndInvalidPort() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new TushareControlledAcceptanceDatabasePreparationPlan(
                                Mode.PREPARATION_ONLY, COMMIT, 25432,
                                "stock_quant_research", "", ExecutionScope.COMMAND_LINE)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new TushareControlledAcceptanceDatabasePreparationPlan(
                                Mode.PREPARATION_ONLY, "not-a-commit", 25432,
                                "postgres", "", ExecutionScope.COMMAND_LINE)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new TushareControlledAcceptanceDatabasePreparationPlan(
                                Mode.PREPARATION_ONLY, COMMIT, 0,
                                "postgres", "", ExecutionScope.COMMAND_LINE)));
    }

    @Test
    void temporaryExecutionScopeCannotAuthorizeFormalMode() {
        assertThrows(IllegalArgumentException.class,
                () -> new TushareControlledAcceptanceDatabasePreparationPlan(
                        Mode.CONTROLLED_DATABASE_PREPARATION, COMMIT, 25432,
                        "postgres", "USER_APPROVED_DBPREP",
                        ExecutionScope.TEMPORARY_POSTGRES_TEST));
    }

    @Test
    void dataSourceRejectsCredentialOverrideAndInvalidTarget() {
        char[] password = "dedicated-secret-value".toCharArray();
        try (var source =
                     new TushareControlledAcceptanceDatabasePreparationDataSource(
                             25432, "stock_quant_research", "stock_quant_research",
                             "tushare_research", password)) {
            assertThrows(Exception.class,
                    () -> source.getConnection("override", "forbidden"));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new TushareControlledAcceptanceDatabasePreparationDataSource(
                        25432, "stock-quant-research", "stock_quant_research",
                        "tushare_research", password));
        java.util.Arrays.fill(password, '\0');
    }

    @Test
    void authorizationRejectsMissingFieldsAndInvalidTypes() {
        Properties missing = authorizationProperties();
        missing.remove("build.proof.path");
        assertThrows(IllegalArgumentException.class,
                () -> TushareControlledAcceptanceLaunchPlan.from(missing));

        Properties invalidPort = authorizationProperties();
        invalidPort.setProperty("database.port", "not-a-number");
        assertThrows(IllegalArgumentException.class,
                () -> TushareControlledAcceptanceLaunchPlan.from(invalidPort));
    }

    @Test
    void authorizationFileRejectsWhitespaceAndEmbeddedEquals(@TempDir Path temp)
            throws Exception {
        List<String> lines = new ArrayList<>();
        Properties properties = authorizationProperties();
        properties.stringPropertyNames().stream().sorted()
                .forEach(key -> lines.add(key + '=' + properties.getProperty(key)));
        lines.set(0, lines.get(0) + " ");
        Path whitespace = temp.resolve("whitespace.properties");
        Files.write(whitespace, lines, StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> TushareControlledAcceptanceLaunchPlan.load(whitespace));

        lines.set(0, "acceptance.id=invalid=duplicate-separator");
        Path embedded = temp.resolve("embedded.properties");
        Files.write(embedded, lines, StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> TushareControlledAcceptanceLaunchPlan.load(embedded));
    }

    @Test
    void outputAuditRejectsIncompleteDatabaseSecretRegistration() {
        assertThrows(
                TushareControlledAcceptanceOutputAudit.CapturedExecutionException.class,
                () -> TushareControlledAcceptanceOutputAudit
                        .captureDatabasePreparationProcess(registry -> {
                            registry.requireDatabasePreparationSecrets();
                            char[] secret = "administrator-secret".toCharArray();
                            try {
                                registry.register(
                                        TushareControlledAcceptanceOutputAudit
                                                .SensitiveKind
                                                .ADMINISTRATOR_DATABASE_PASSWORD,
                                        secret);
                            } finally {
                                java.util.Arrays.fill(secret, '\0');
                            }
                            return "incomplete";
                        }));
    }

    @Test
    void candidateReportRejectsSecretAndJdbcShapedValues() {
        Instant now = Instant.parse("2026-08-03T06:00:00Z");
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new PreparationReport(
                                "DATABASE_PREPARATION_CANDIDATE", Mode.PREPARATION_ONLY,
                                COMMIT, 25432, "password-owner", "NOT_CONNECTED",
                                List.of(), false, false, false, false,
                                now, now, Phase.NON_SECRET_PLAN_VALIDATED, "SAFE")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new PreparationReport(
                                "DATABASE_PREPARATION_CANDIDATE", Mode.PREPARATION_ONLY,
                                COMMIT, 25432, "postgres", "jdbc:forbidden",
                                List.of(), false, false, false, false,
                                now, now, Phase.NON_SECRET_PLAN_VALIDATED, "SAFE")));
    }

    @Test
    void preparationReportDistinguishesUnverifiedAndVerifiedPrivileges() {
        String preparation = report(Mode.PREPARATION_ONLY).render(true);
        assertTrue(preparation.contains("PUBLIC_PRIVILEGES_REVOKED=NOT_VERIFIED"));
        assertTrue(preparation.contains("DEDICATED_ROLE_PRIVILEGES=NOT_VERIFIED"));

        Instant now = Instant.parse("2026-08-03T06:00:00Z");
        String verified = new PreparationReport(
                "DATABASE_PREPARATION_CANDIDATE", Mode.PREPARATION_ONLY,
                COMMIT, 25432, "postgres", "16.13",
                TushareControlledAcceptanceDatabasePreparationService
                        .EXPECTED_MAIN_MIGRATIONS,
                false, false, false, false,
                now, now, Phase.READBACK_VERIFIED,
                "TEMPORARY_POSTGRES_TEST_ONLY").render(true);
        assertTrue(verified.contains("PUBLIC_PRIVILEGES_REVOKED=true"));
        assertTrue(verified.contains("CONNECTION_LIMIT_4"));
    }

    private static TushareControlledAcceptanceDatabasePreparationPlan preparationPlan() {
        return new TushareControlledAcceptanceDatabasePreparationPlan(
                Mode.PREPARATION_ONLY, COMMIT, 25432, "postgres", "",
                ExecutionScope.COMMAND_LINE);
    }

    private static TushareControlledAcceptanceDatabasePreparationPlan formalPlan() {
        return new TushareControlledAcceptanceDatabasePreparationPlan(
                Mode.CONTROLLED_DATABASE_PREPARATION, COMMIT, 25432,
                "postgres", "USER_APPROVED_DBPREP", ExecutionScope.COMMAND_LINE);
    }

    private static PreparationReport report(Mode mode) {
        Instant now = Instant.parse("2026-08-03T06:00:00Z");
        return new PreparationReport(
                "DATABASE_PREPARATION_CANDIDATE", mode, COMMIT, 25432,
                "postgres", "NOT_CONNECTED", List.of(), false, false,
                false, false, now, now, Phase.NON_SECRET_PLAN_VALIDATED,
                "PREPARATION_ONLY_NO_DATABASE_MUTATION");
    }

    private static Properties authorizationProperties() {
        Properties properties = new Properties();
        properties.setProperty("authorization.status", "USER_APPROVED");
        properties.setProperty("authorization.version", "F1F_B2_AUTHORIZATION_V1");
        properties.setProperty("acceptance.id", "F1FB2_NEW_AUTHORIZED_ID");
        properties.setProperty("git.commit", COMMIT);
        properties.setProperty("artifact.sha256", "b".repeat(64));
        properties.setProperty("build.proof.path",
                Path.of("target", "runner.jar.f1f-b2-proof.properties").toString());
        properties.setProperty("provider.code", "TUSHARE_PRO");
        properties.setProperty("security.symbol", "600000");
        properties.setProperty("security.exchange", "SSE");
        properties.setProperty("trade.date", "2025-01-02");
        properties.setProperty("endpoints", "daily,adj_factor,trade_cal");
        properties.setProperty("maximum.provider.requests", "3");
        properties.setProperty("retry.budget", "0");
        properties.setProperty("database.host", "127.0.0.1");
        properties.setProperty("database.name", "stock_quant_research");
        properties.setProperty("database.user", "stock_quant_research");
        properties.setProperty("database.port", "25432");
        properties.setProperty("database.ssl.mode", "DISABLE_LOCAL_ONLY");
        properties.setProperty("schema.name", "tushare_research");
        properties.setProperty("base.schema.version", "13");
        properties.setProperty("governance.schema.version", "14");
        properties.setProperty("issued.at", "2026-08-03T06:00:00Z");
        properties.setProperty("expires.at", "2026-08-03T06:10:00Z");
        properties.setProperty("purpose", "F1F_B2_CONTROLLED_ACCEPTANCE");
        properties.setProperty("execution.source", "REAL_CONTROLLED_ACCEPTANCE");
        properties.setProperty("user.approval.reference", "USER_APPROVED");
        return properties;
    }

    private static class FakeEnvironment
            implements TushareControlledAcceptanceDatabasePreparer.PreparationEnvironment {
        final List<String> events = new ArrayList<>();
        final PrintStream originalOut = System.out;
        final TushareControlledAcceptanceDatabasePreparationPlan plan;
        SecretValue adminValue;
        SecretValue dedicatedValue;
        char[] receivedAdmin;
        char[] receivedDedicated;
        boolean auditAtPlan;
        boolean auditAtAdminSecret;
        boolean auditAtPrepare;

        FakeEnvironment(TushareControlledAcceptanceDatabasePreparationPlan plan) {
            this.plan = plan;
        }

        @Override
        public TushareControlledAcceptanceDatabasePreparationPlan loadPlan(String[] args) {
            events.add("plan");
            auditAtPlan = System.out != originalOut;
            return plan;
        }

        @Override
        public TushareControlledAcceptanceSecretChannel secretChannel() {
            events.add("secret-channel");
            return new TushareControlledAcceptanceSecretChannel() {
                @Override
                public SecretValue readDatabasePassword() {
                    throw new AssertionError("runner database secret not allowed");
                }

                @Override
                public SecretValue readTushareToken() {
                    throw new AssertionError("Provider token not allowed");
                }

                @Override
                public SecretValue readAdministratorDatabasePassword() {
                    events.add("admin-secret");
                    auditAtAdminSecret = System.out != originalOut;
                    adminValue = new SecretValue("admin-secret-value".toCharArray());
                    return adminValue;
                }

                @Override
                public SecretValue readDedicatedDatabasePassword() {
                    events.add("dedicated-secret");
                    dedicatedValue = new SecretValue(
                            "dedicated-secret-value".toCharArray());
                    return dedicatedValue;
                }
            };
        }

        @Override
        public PreparationReport validateOnly(
                TushareControlledAcceptanceDatabasePreparationPlan ignored
        ) {
            events.add("validate-only");
            return report(Mode.PREPARATION_ONLY);
        }

        @Override
        public PreparationReport prepare(
                TushareControlledAcceptanceDatabasePreparationPlan ignored,
                char[] administratorPassword,
                TushareControlledAcceptanceDatabasePreparationService
                        .DedicatedPasswordSupplier dedicatedPasswordSupplier,
                TushareControlledAcceptanceDatabasePreparationService
                        .BootstrapSecretRegistrar bootstrapSecretRegistrar
        ) {
            events.add("prepare-admin");
            char[] bootstrap = "random-bootstrap-secret-value".toCharArray();
            bootstrapSecretRegistrar.register(bootstrap);
            events.add("bootstrap-secret");
            java.util.Arrays.fill(bootstrap, '\0');
            receivedAdmin = administratorPassword;
            java.util.Arrays.fill(administratorPassword, '\0');
            char[] dedicatedPassword = dedicatedPasswordSupplier.read();
            events.add("prepare");
            auditAtPrepare = System.out != originalOut;
            assertTrue(adminValue.cleared());
            assertTrue(dedicatedValue.cleared());
            receivedDedicated = dedicatedPassword;
            java.util.Arrays.fill(dedicatedPassword, '\0');
            return report(Mode.CONTROLLED_DATABASE_PREPARATION);
        }

        boolean copiesClearedAfterReturn() {
            return receivedAdmin != null && receivedDedicated != null
                    && java.util.Arrays.equals(receivedAdmin,
                    new char[receivedAdmin.length])
                    && java.util.Arrays.equals(receivedDedicated,
                    new char[receivedDedicated.length]);
        }
    }
}

package com.stockquant.server.agent.marketfacts;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

/** Verifies the proof sidecar against the JAR that contains the executor. */
public final class TushareControlledAcceptanceBuildProof {
    static final String SIDECAR_SUFFIX = ".f1f-b2-proof.properties";
    static final String MODULE_VERSION = "1.3.1";
    static final String MAVEN_WRAPPER_VERSION = "3.9.16";
    static final String REQUIRED_INTEGRATION_BRANCH = "feature/1.4.0-agent-team";
    static final String BOOT_MAIN_CLASS =
            "org.springframework.boot.loader.launch.JarLauncher";
    static final String F1F_B2_RUNNER_START_CLASS =
            "com.stockquant.server.agent.marketfacts."
                    + "TushareControlledAcceptanceRunner";
    static final String DAY001_RUNNER_START_CLASS =
            "com.stockquant.server.agent.marketfacts."
                    + "TushareReducedResearchManualRunner";
    static final String M1_RUNNER_START_CLASS =
            "com.stockquant.server.agent.marketfacts."
                    + "TushareM1ResearchDataManualRunner";
    static final String M2_RUNNER_START_CLASS =
            "com.stockquant.server.agent.marketfacts."
                    + "TushareM2StrategyResearchManualRunner";
    static final String M3_RUNNER_START_CLASS =
            "com.stockquant.server.agent.marketfacts."
                    + "TushareM3AgentResearchManualRunner";
    static final String M4_RUNNER_START_CLASS =
            "com.stockquant.server.agent.marketfacts."
                    + "TushareM4ShadowResearchManualRunner";
    public static final String M6_RUNNER_START_CLASS =
            "com.stockquant.server.production."
                    + "StockQuantResearchProductionRunner";
    public static final String RESEARCH_SELECTION_RUNNER_START_CLASS =
            "com.stockquant.server.agent.marketfacts."
                    + "TushareResearchSelectionManualRunner";
    public static final String MAINBOARD_DAILY_INCREMENT_RUNNER_START_CLASS =
            "com.stockquant.server.agent.marketfacts."
                    + "TushareMainboardDailyIncrementManualRunner";
    public static final String MAINBOARD_HISTORY_BACKFILL_RUNNER_START_CLASS =
            "com.stockquant.server.agent.marketfacts."
                    + "TushareMainboardHistoryBackfillManualRunner";
    public static final String
            MAINBOARD_TRADE_CAL_BACKFILL_RUNNER_START_CLASS =
            "com.stockquant.server.agent.marketfacts."
                    + "TushareMainboardTradeCalendarBackfillManualRunner";
    static final String M1_STAGE_BRANCH =
            "codex/1.4.0-m1-research-data-ready";
    private static final Set<String> REQUIRED_PROPERTIES = Set.of(
            "git.commit", "git.remote.commit", "git.branch", "git.trackedClean",
            "git.untrackedScopeClean",
            "artifact.sha256", "build.time", "java.version", "module.version",
            "maven.wrapper.version", "build.mode", "executor.version",
            "qualification.rule.version");

    private TushareControlledAcceptanceBuildProof() {
    }

    /**
     * Loads only the sidecar adjacent to the actual JAR from which this class
     * is running. A caller cannot substitute an arbitrary artifact path.
     */
    public static VerifiedBuildProof loadCurrentExecutorArtifact() {
        Path artifact = currentExecutorArtifact();
        return loadBoundArtifact(
                artifact,
                Path.of(artifact.toString() + SIDECAR_SUFFIX),
                null);
    }

    public static VerifiedBuildProof loadCurrentExecutorArtifact(
            Path declaredProofPath
    ) {
        Path artifact = currentExecutorArtifact();
        Path expected = Path.of(artifact.toString() + SIDECAR_SUFFIX)
                .toAbsolutePath().normalize();
        if (declaredProofPath == null
                || !expected.equals(declaredProofPath.toAbsolutePath().normalize())) {
            throw invalid("TUSHARE_CONTROLLED_ACCEPTANCE_BUILD_PROOF_PATH_INVALID");
        }
        return loadBoundArtifact(artifact, expected, null);
    }

    static VerifiedBuildProof loadBoundTestArtifact(Path artifact, Path sidecar) {
        return loadBoundArtifact(artifact, sidecar, ProofSource.TEST_ONLY);
    }

    static VerifiedBuildProof loadBoundPreparationArtifactForTest(
            Path artifact,
            Path sidecar
    ) {
        return loadBoundArtifact(artifact, sidecar, null);
    }

    private static VerifiedBuildProof loadBoundArtifact(
            Path artifact,
            Path proofSidecar,
            ProofSource source
    ) {
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(proofSidecar, "proofSidecar");
        Path normalizedArtifact = artifact.toAbsolutePath().normalize();
        Path expectedSidecar = Path.of(normalizedArtifact + SIDECAR_SUFFIX);
        if (!proofSidecar.toAbsolutePath().normalize().equals(expectedSidecar)
                || !Files.isRegularFile(normalizedArtifact)
                || !Files.isRegularFile(expectedSidecar)
                || !normalizedArtifact.getFileName().toString().endsWith(".jar")) {
            throw invalid("TUSHARE_CONTROLLED_ACCEPTANCE_BUILD_PROOF_MISSING");
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(expectedSidecar)) {
            properties.load(input);
        } catch (IOException error) {
            throw invalid("TUSHARE_CONTROLLED_ACCEPTANCE_BUILD_PROOF_UNREADABLE");
        }
        if (!properties.stringPropertyNames().equals(REQUIRED_PROPERTIES)) {
            throw invalid("TUSHARE_CONTROLLED_ACCEPTANCE_BUILD_PROOF_FIELDS_INVALID");
        }
        ManifestProof manifest = readManifest(normalizedArtifact);
        String actualArtifactSha = sha256(normalizedArtifact);
        VerifiedBuildProof proof;
        try {
            BuildMode buildMode = BuildMode.valueOf(
                    properties.getProperty("build.mode"));
            ProofSource effectiveSource = source == ProofSource.TEST_ONLY
                    ? ProofSource.TEST_ONLY
                    : buildMode == BuildMode.CONTROLLED_BUILD_ARTIFACT
                    ? ProofSource.CONTROLLED_BUILD_ARTIFACT
                    : buildMode
                    == BuildMode.M1_STAGE_CONTROLLED_BUILD_ARTIFACT
                    ? ProofSource.M1_STAGE_CONTROLLED_BUILD_ARTIFACT
                    : buildMode
                    == BuildMode.M2_STAGE_CONTROLLED_BUILD_ARTIFACT
                    ? ProofSource.M2_STAGE_CONTROLLED_BUILD_ARTIFACT
                    : buildMode
                    == BuildMode.M3_STAGE_CONTROLLED_BUILD_ARTIFACT
                    ? ProofSource.M3_STAGE_CONTROLLED_BUILD_ARTIFACT
                    : buildMode
                    == BuildMode.M4_STAGE_CONTROLLED_BUILD_ARTIFACT
                    ? ProofSource.M4_STAGE_CONTROLLED_BUILD_ARTIFACT
                    : buildMode
                    == BuildMode.M6_STAGE_CONTROLLED_BUILD_ARTIFACT
                    ? ProofSource.M6_STAGE_CONTROLLED_BUILD_ARTIFACT
                    : buildMode
                    == BuildMode.RESEARCH_SELECTION_CONTROLLED_BUILD_ARTIFACT
                    ? ProofSource.RESEARCH_SELECTION_CONTROLLED_BUILD_ARTIFACT
                    : buildMode == BuildMode.E2E_DRY_RUN
                    ? ProofSource.E2E_DRY_RUN
                    : ProofSource.PREPARATION_ONLY;
            proof = new VerifiedBuildProof(
                    properties.getProperty("git.commit"),
                    properties.getProperty("git.remote.commit"),
                    properties.getProperty("git.branch"),
                    Boolean.parseBoolean(properties.getProperty("git.trackedClean")),
                    Boolean.parseBoolean(properties.getProperty("git.untrackedScopeClean")),
                    actualArtifactSha,
                    properties.getProperty("artifact.sha256"),
                    Instant.parse(properties.getProperty("build.time")),
                    properties.getProperty("java.version"),
                    properties.getProperty("module.version"),
                    properties.getProperty("maven.wrapper.version"),
                    buildMode,
                    properties.getProperty("executor.version"),
                    properties.getProperty("qualification.rule.version"),
                    manifest,
                    effectiveSource);
        } catch (DateTimeParseException | IllegalArgumentException error) {
            throw invalid("TUSHARE_CONTROLLED_ACCEPTANCE_BUILD_PROOF_FIELDS_INVALID");
        }
        proof.validate();
        return proof;
    }

    static VerifiedBuildProof verifiedTestProof(
            String gitCommit,
            String artifactSha256
    ) {
        ManifestProof manifest = new ManifestProof(
                BOOT_MAIN_CLASS, F1F_B2_RUNNER_START_CLASS,
                gitCommit, gitCommit, REQUIRED_INTEGRATION_BRANCH, true, true,
                Instant.parse("2026-08-01T00:00:00Z"),
                currentJavaVersion(), MODULE_VERSION, MAVEN_WRAPPER_VERSION,
                BuildMode.CONTROLLED_BUILD_ARTIFACT,
                TushareControlledAcceptanceExecution.EXECUTOR_VERSION,
                TushareControlledAcceptanceExecution.RULE_VERSION);
        return new VerifiedBuildProof(
                gitCommit, gitCommit, REQUIRED_INTEGRATION_BRANCH, true, true,
                artifactSha256, artifactSha256,
                Instant.parse("2026-08-01T00:00:00Z"),
                currentJavaVersion(), MODULE_VERSION, MAVEN_WRAPPER_VERSION,
                BuildMode.CONTROLLED_BUILD_ARTIFACT,
                TushareControlledAcceptanceExecution.EXECUTOR_VERSION,
                TushareControlledAcceptanceExecution.RULE_VERSION,
                manifest, ProofSource.TEST_ONLY);
    }

    static VerifiedBuildProof verifiedDay001TestProof(
            String gitCommit,
            String artifactSha256
    ) {
        ManifestProof manifest = new ManifestProof(
                BOOT_MAIN_CLASS, DAY001_RUNNER_START_CLASS,
                gitCommit, gitCommit, REQUIRED_INTEGRATION_BRANCH, true, true,
                Instant.parse("2026-08-01T00:00:00Z"),
                currentJavaVersion(), MODULE_VERSION, MAVEN_WRAPPER_VERSION,
                BuildMode.E2E_DRY_RUN,
                TushareControlledAcceptanceExecution.EXECUTOR_VERSION,
                TushareControlledAcceptanceExecution.RULE_VERSION);
        return new VerifiedBuildProof(
                gitCommit, gitCommit, REQUIRED_INTEGRATION_BRANCH, true, true,
                artifactSha256, artifactSha256,
                Instant.parse("2026-08-01T00:00:00Z"),
                currentJavaVersion(), MODULE_VERSION, MAVEN_WRAPPER_VERSION,
                BuildMode.E2E_DRY_RUN,
                TushareControlledAcceptanceExecution.EXECUTOR_VERSION,
                TushareControlledAcceptanceExecution.RULE_VERSION,
                manifest, ProofSource.E2E_DRY_RUN);
    }

    public static final class VerifiedBuildProof {
        private final String gitCommit;
        private final String remoteGitCommit;
        private final String branchName;
        private final boolean trackedWorkspaceClean;
        private final boolean untrackedScopeClean;
        private final String actualArtifactSha256;
        private final String declaredArtifactSha256;
        private final Instant buildTime;
        private final String javaVersion;
        private final String moduleVersion;
        private final String mavenWrapperVersion;
        private final BuildMode buildMode;
        private final String executorVersion;
        private final String qualificationRuleVersion;
        private final ManifestProof manifest;
        private final ProofSource source;

        private VerifiedBuildProof(
                String gitCommit,
                String remoteGitCommit,
                String branchName,
                boolean trackedWorkspaceClean,
                boolean untrackedScopeClean,
                String actualArtifactSha256,
                String declaredArtifactSha256,
                Instant buildTime,
                String javaVersion,
                String moduleVersion,
                String mavenWrapperVersion,
                BuildMode buildMode,
                String executorVersion,
                String qualificationRuleVersion,
                ManifestProof manifest,
                ProofSource source
        ) {
            this.gitCommit = TushareControlledAcceptanceExecution.commit(gitCommit);
            this.remoteGitCommit = TushareControlledAcceptanceExecution.commit(
                    remoteGitCommit);
            this.branchName = Objects.requireNonNull(branchName, "branchName");
            this.trackedWorkspaceClean = trackedWorkspaceClean;
            this.untrackedScopeClean = untrackedScopeClean;
            this.actualArtifactSha256 = TushareControlledAcceptanceExecution.sha256(
                    actualArtifactSha256);
            this.declaredArtifactSha256 = TushareControlledAcceptanceExecution.sha256(
                    declaredArtifactSha256);
            this.buildTime = Objects.requireNonNull(buildTime, "buildTime");
            this.javaVersion = TushareControlledAcceptanceExecution.safeText(javaVersion);
            this.moduleVersion = TushareControlledAcceptanceExecution.safeText(moduleVersion);
            this.mavenWrapperVersion = TushareControlledAcceptanceExecution.safeText(
                    mavenWrapperVersion);
            this.buildMode = Objects.requireNonNull(buildMode, "buildMode");
            this.executorVersion = TushareControlledAcceptanceExecution.safeText(executorVersion);
            this.qualificationRuleVersion = TushareControlledAcceptanceExecution.safeText(
                    qualificationRuleVersion);
            this.manifest = Objects.requireNonNull(manifest, "manifest");
            this.source = Objects.requireNonNull(source, "source");
        }

        void validate() {
            if (!trackedWorkspaceClean || !untrackedScopeClean
                    || !actualArtifactSha256.equals(declaredArtifactSha256)
                    || !BOOT_MAIN_CLASS.equals(manifest.mainClass())
                    || !Set.of(F1F_B2_RUNNER_START_CLASS,
                    DAY001_RUNNER_START_CLASS,
                    M1_RUNNER_START_CLASS,
                    M2_RUNNER_START_CLASS,
                    M3_RUNNER_START_CLASS,
                    M4_RUNNER_START_CLASS,
                    M6_RUNNER_START_CLASS,
                    RESEARCH_SELECTION_RUNNER_START_CLASS,
                    MAINBOARD_DAILY_INCREMENT_RUNNER_START_CLASS,
                    MAINBOARD_HISTORY_BACKFILL_RUNNER_START_CLASS,
                    MAINBOARD_TRADE_CAL_BACKFILL_RUNNER_START_CLASS).contains(
                            manifest.startClass())
                    || !gitCommit.equals(manifest.gitCommit())
                    || !remoteGitCommit.equals(manifest.remoteGitCommit())
                    || !branchAllowedForMode(branchName, buildMode)
                    || buildMode == BuildMode.CONTROLLED_BUILD_ARTIFACT
                    && !gitCommit.equals(remoteGitCommit)
                    || buildMode
                    == BuildMode.M1_STAGE_CONTROLLED_BUILD_ARTIFACT
                    && !gitCommit.equals(remoteGitCommit)
                    || buildMode
                    == BuildMode.M2_STAGE_CONTROLLED_BUILD_ARTIFACT
                    && !gitCommit.equals(remoteGitCommit)
                    || buildMode
                    == BuildMode.M3_STAGE_CONTROLLED_BUILD_ARTIFACT
                    && !gitCommit.equals(remoteGitCommit)
                    || buildMode
                    == BuildMode.M4_STAGE_CONTROLLED_BUILD_ARTIFACT
                    && !gitCommit.equals(remoteGitCommit)
                    || buildMode
                    == BuildMode.M6_STAGE_CONTROLLED_BUILD_ARTIFACT
                    && !gitCommit.equals(remoteGitCommit)
                    || buildMode
                    == BuildMode.RESEARCH_SELECTION_CONTROLLED_BUILD_ARTIFACT
                    && !gitCommit.equals(remoteGitCommit)
                    || !branchName.equals(manifest.branchName())
                    || trackedWorkspaceClean != manifest.trackedWorkspaceClean()
                    || untrackedScopeClean != manifest.untrackedScopeClean()
                    || !buildTime.equals(manifest.buildTime())
                    || !javaVersion.equals(manifest.javaVersion())
                    || !javaVersion.equals(currentJavaVersion())
                    || !MODULE_VERSION.equals(moduleVersion)
                    || !moduleVersion.equals(manifest.moduleVersion())
                    || !MAVEN_WRAPPER_VERSION.equals(mavenWrapperVersion)
                    || !mavenWrapperVersion.equals(manifest.mavenWrapperVersion())
                    || buildMode != manifest.buildMode()
                    || !TushareControlledAcceptanceExecution.EXECUTOR_VERSION.equals(
                    executorVersion)
                    || !executorVersion.equals(manifest.executorVersion())
                    || !TushareControlledAcceptanceExecution.RULE_VERSION.equals(
                    qualificationRuleVersion)
                    || !qualificationRuleVersion.equals(
                    manifest.qualificationRuleVersion())) {
                throw invalid("TUSHARE_CONTROLLED_ACCEPTANCE_BUILD_PROOF_INVALID");
            }
        }

        boolean governanceEligible() {
            validate();
            return source == ProofSource.CONTROLLED_BUILD_ARTIFACT
                    && buildMode == BuildMode.CONTROLLED_BUILD_ARTIFACT;
        }

        boolean e2eDryRunEligible() {
            validate();
            return source == ProofSource.E2E_DRY_RUN
                    && buildMode == BuildMode.E2E_DRY_RUN;
        }

        boolean m1StageEligible() {
            validate();
            return source
                    == ProofSource.M1_STAGE_CONTROLLED_BUILD_ARTIFACT
                    && buildMode
                    == BuildMode.M1_STAGE_CONTROLLED_BUILD_ARTIFACT
                    && M1_RUNNER_START_CLASS.equals(runnerStartClass());
        }

        boolean m2StageEligible() {
            validate();
            return source
                    == ProofSource.M2_STAGE_CONTROLLED_BUILD_ARTIFACT
                    && buildMode
                    == BuildMode.M2_STAGE_CONTROLLED_BUILD_ARTIFACT
                    && M2_RUNNER_START_CLASS.equals(runnerStartClass());
        }

        boolean m3StageEligible() {
            validate();
            return source
                    == ProofSource.M3_STAGE_CONTROLLED_BUILD_ARTIFACT
                    && buildMode
                    == BuildMode.M3_STAGE_CONTROLLED_BUILD_ARTIFACT
                    && M3_RUNNER_START_CLASS.equals(runnerStartClass());
        }

        boolean m4StageEligible() {
            validate();
            return source
                    == ProofSource.M4_STAGE_CONTROLLED_BUILD_ARTIFACT
                    && buildMode
                    == BuildMode.M4_STAGE_CONTROLLED_BUILD_ARTIFACT
                    && M4_RUNNER_START_CLASS.equals(runnerStartClass());
        }

        public boolean m6StageEligible() {
            validate();
            return source
                    == ProofSource.M6_STAGE_CONTROLLED_BUILD_ARTIFACT
                    && buildMode
                    == BuildMode.M6_STAGE_CONTROLLED_BUILD_ARTIFACT
                    && M6_RUNNER_START_CLASS.equals(runnerStartClass());
        }

        public boolean m6ProductionEligible() {
            validate();
            return M6_RUNNER_START_CLASS.equals(runnerStartClass())
                    && (source
                    == ProofSource.M6_STAGE_CONTROLLED_BUILD_ARTIFACT
                    && buildMode
                    == BuildMode.M6_STAGE_CONTROLLED_BUILD_ARTIFACT
                    || source == ProofSource.CONTROLLED_BUILD_ARTIFACT
                    && buildMode == BuildMode.CONTROLLED_BUILD_ARTIFACT
                    || source
                    == ProofSource.RESEARCH_SELECTION_CONTROLLED_BUILD_ARTIFACT
                    && buildMode
                    == BuildMode.RESEARCH_SELECTION_CONTROLLED_BUILD_ARTIFACT);
        }

        public boolean researchSelectionEligible() {
            validate();
            return RESEARCH_SELECTION_RUNNER_START_CLASS.equals(
                    runnerStartClass()) && (source
                    == ProofSource.RESEARCH_SELECTION_CONTROLLED_BUILD_ARTIFACT
                    && buildMode
                    == BuildMode.RESEARCH_SELECTION_CONTROLLED_BUILD_ARTIFACT
                    || source == ProofSource.CONTROLLED_BUILD_ARTIFACT
                    && buildMode == BuildMode.CONTROLLED_BUILD_ARTIFACT);
        }

        public boolean mainboardDailyIncrementEligible() {
            validate();
            return MAINBOARD_DAILY_INCREMENT_RUNNER_START_CLASS.equals(
                    runnerStartClass()) && (source
                    == ProofSource.RESEARCH_SELECTION_CONTROLLED_BUILD_ARTIFACT
                    && buildMode
                    == BuildMode.RESEARCH_SELECTION_CONTROLLED_BUILD_ARTIFACT
                    || source == ProofSource.CONTROLLED_BUILD_ARTIFACT
                    && buildMode == BuildMode.CONTROLLED_BUILD_ARTIFACT);
        }

        public boolean mainboardHistoryBackfillEligible() {
            validate();
            return MAINBOARD_HISTORY_BACKFILL_RUNNER_START_CLASS.equals(
                    runnerStartClass()) && (source
                    == ProofSource.RESEARCH_SELECTION_CONTROLLED_BUILD_ARTIFACT
                    && buildMode
                    == BuildMode.RESEARCH_SELECTION_CONTROLLED_BUILD_ARTIFACT
                    || source == ProofSource.CONTROLLED_BUILD_ARTIFACT
                    && buildMode == BuildMode.CONTROLLED_BUILD_ARTIFACT);
        }

        public boolean mainboardTradeCalendarBackfillEligible() {
            validate();
            return MAINBOARD_TRADE_CAL_BACKFILL_RUNNER_START_CLASS.equals(
                    runnerStartClass()) && (source
                    == ProofSource.RESEARCH_SELECTION_CONTROLLED_BUILD_ARTIFACT
                    && buildMode
                    == BuildMode.RESEARCH_SELECTION_CONTROLLED_BUILD_ARTIFACT
                    || source == ProofSource.CONTROLLED_BUILD_ARTIFACT
                    && buildMode == BuildMode.CONTROLLED_BUILD_ARTIFACT);
        }

        boolean m6ShadowStageEligible() {
            validate();
            return source
                    == ProofSource.M6_STAGE_CONTROLLED_BUILD_ARTIFACT
                    && buildMode
                    == BuildMode.M6_STAGE_CONTROLLED_BUILD_ARTIFACT
                    && M4_RUNNER_START_CLASS.equals(runnerStartClass());
        }

        public String gitCommit() { return gitCommit; }
        public String remoteGitCommit() { return remoteGitCommit; }
        public String branchName() { return branchName; }
        public boolean trackedWorkspaceClean() { return trackedWorkspaceClean; }
        public boolean untrackedScopeClean() { return untrackedScopeClean; }
        public String actualArtifactSha256() { return actualArtifactSha256; }
        public String declaredArtifactSha256() { return declaredArtifactSha256; }
        public Instant buildTime() { return buildTime; }
        public String javaVersion() { return javaVersion; }
        public String moduleVersion() { return moduleVersion; }
        public String mavenWrapperVersion() { return mavenWrapperVersion; }
        public BuildMode buildMode() { return buildMode; }
        public String executorVersion() { return executorVersion; }
        public String qualificationRuleVersion() { return qualificationRuleVersion; }
        public String runnerStartClass() { return manifest.startClass(); }
        public ProofSource source() { return source; }

        @Override
        public String toString() {
            return "VerifiedBuildProof[gitCommit=" + gitCommit
                    + ", remoteGitCommit=" + remoteGitCommit
                    + ", branchName=" + branchName
                    + ", artifactSha256=[SHA256], buildMode=" + buildMode
                    + ", source=" + source + ']';
        }
    }

    record ManifestProof(
            String mainClass,
            String startClass,
            String gitCommit,
            String remoteGitCommit,
            String branchName,
            boolean trackedWorkspaceClean,
            boolean untrackedScopeClean,
            Instant buildTime,
            String javaVersion,
            String moduleVersion,
            String mavenWrapperVersion,
            BuildMode buildMode,
            String executorVersion,
            String qualificationRuleVersion
    ) {
        ManifestProof {
            mainClass = TushareControlledAcceptanceExecution.safeText(mainClass);
            startClass = TushareControlledAcceptanceExecution.safeText(startClass);
            gitCommit = TushareControlledAcceptanceExecution.commit(gitCommit);
            remoteGitCommit = TushareControlledAcceptanceExecution.commit(remoteGitCommit);
            branchName = Objects.requireNonNull(branchName, "branchName");
            buildTime = Objects.requireNonNull(buildTime, "buildTime");
            javaVersion = TushareControlledAcceptanceExecution.safeText(javaVersion);
            moduleVersion = TushareControlledAcceptanceExecution.safeText(moduleVersion);
            mavenWrapperVersion = TushareControlledAcceptanceExecution.safeText(
                    mavenWrapperVersion);
            buildMode = Objects.requireNonNull(buildMode, "buildMode");
            executorVersion = TushareControlledAcceptanceExecution.safeText(executorVersion);
            qualificationRuleVersion = TushareControlledAcceptanceExecution.safeText(
                    qualificationRuleVersion);
        }
    }

    public enum ProofSource {
        CONTROLLED_BUILD_ARTIFACT,
        M1_STAGE_CONTROLLED_BUILD_ARTIFACT,
        M2_STAGE_CONTROLLED_BUILD_ARTIFACT,
        M3_STAGE_CONTROLLED_BUILD_ARTIFACT,
        M4_STAGE_CONTROLLED_BUILD_ARTIFACT,
        M6_STAGE_CONTROLLED_BUILD_ARTIFACT,
        RESEARCH_SELECTION_CONTROLLED_BUILD_ARTIFACT,
        E2E_DRY_RUN,
        PREPARATION_ONLY,
        TEST_ONLY
    }

    public enum BuildMode {
        PREPARATION_ONLY,
        CONTROLLED_BUILD_ARTIFACT,
        M1_STAGE_CONTROLLED_BUILD_ARTIFACT,
        M2_STAGE_CONTROLLED_BUILD_ARTIFACT,
        M3_STAGE_CONTROLLED_BUILD_ARTIFACT,
        M4_STAGE_CONTROLLED_BUILD_ARTIFACT,
        M6_STAGE_CONTROLLED_BUILD_ARTIFACT,
        RESEARCH_SELECTION_CONTROLLED_BUILD_ARTIFACT,
        E2E_DRY_RUN
    }

    private static Path currentExecutorArtifact() {
        return requireSingleJarClasspath(System.getProperty("java.class.path"));
    }

    static Path requireSingleJarClasspath(String classPath) {
        if (classPath == null || classPath.isBlank()) {
            throw invalid("TUSHARE_CONTROLLED_ACCEPTANCE_RUNNING_ARTIFACT_INVALID");
        }
        String[] entries = classPath.split(
                java.util.regex.Pattern.quote(System.getProperty("path.separator")), -1);
        if (entries.length != 1 || entries[0].isBlank()) {
            throw invalid("TUSHARE_CONTROLLED_ACCEPTANCE_RUNNING_ARTIFACT_INVALID");
        }
        Path path;
        try {
            path = Path.of(entries[0]).toAbsolutePath().normalize();
        } catch (RuntimeException error) {
            throw invalid("TUSHARE_CONTROLLED_ACCEPTANCE_RUNNING_ARTIFACT_INVALID");
        }
        if (!Files.isRegularFile(path)
                || !path.getFileName().toString().endsWith(".jar")) {
            throw invalid("TUSHARE_CONTROLLED_ACCEPTANCE_RUNNING_ARTIFACT_INVALID");
        }
        return path;
    }

    private static ManifestProof readManifest(Path artifact) {
        try (JarFile jar = new JarFile(artifact.toFile())) {
            if (jar.getManifest() == null) {
                throw invalid("TUSHARE_CONTROLLED_ACCEPTANCE_ARTIFACT_MANIFEST_INVALID");
            }
            Attributes attributes = jar.getManifest().getMainAttributes();
            return new ManifestProof(
                    attributes.getValue("Main-Class"),
                    attributes.getValue("Start-Class"),
                    attributes.getValue("Stock-Quant-Git-Commit"),
                    attributes.getValue("Stock-Quant-Git-Remote-Commit"),
                    attributes.getValue("Stock-Quant-Git-Branch"),
                    Boolean.parseBoolean(attributes.getValue("Stock-Quant-Tracked-Clean")),
                    Boolean.parseBoolean(attributes.getValue(
                            "Stock-Quant-Untracked-Scope-Clean")),
                    Instant.parse(attributes.getValue("Stock-Quant-Build-Time")),
                    attributes.getValue("Stock-Quant-Java-Version"),
                    attributes.getValue("Stock-Quant-Module-Version"),
                    attributes.getValue("Stock-Quant-Maven-Wrapper-Version"),
                    BuildMode.valueOf(attributes.getValue("Stock-Quant-Build-Mode")),
                    attributes.getValue("Stock-Quant-Executor-Version"),
                    attributes.getValue("Stock-Quant-Qualification-Rule-Version"));
        } catch (IOException | DateTimeParseException | IllegalArgumentException error) {
            throw invalid("TUSHARE_CONTROLLED_ACCEPTANCE_ARTIFACT_MANIFEST_INVALID");
        }
    }

    private static String sha256(Path artifact) {
        try (InputStream input = Files.newInputStream(artifact)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException error) {
            throw invalid("TUSHARE_CONTROLLED_ACCEPTANCE_ARTIFACT_HASH_FAILED");
        }
    }

    static String currentJavaVersion() {
        return TushareControlledAcceptanceExecution.safeText(
                System.getProperty("java.version"));
    }

    private static boolean branchAllowedForMode(String branchName, BuildMode buildMode) {
        if (buildMode == BuildMode.CONTROLLED_BUILD_ARTIFACT) {
            return REQUIRED_INTEGRATION_BRANCH.equals(branchName);
        }
        if (buildMode == BuildMode.M1_STAGE_CONTROLLED_BUILD_ARTIFACT) {
            return M1_STAGE_BRANCH.equals(branchName);
        }
        if (buildMode == BuildMode.M2_STAGE_CONTROLLED_BUILD_ARTIFACT) {
            return "codex/1.4.0-m2-strategy-engine-ready".equals(branchName);
        }
        if (buildMode == BuildMode.M3_STAGE_CONTROLLED_BUILD_ARTIFACT) {
            return "codex/1.4.0-m3-agent-research-ready".equals(branchName);
        }
        if (buildMode == BuildMode.M4_STAGE_CONTROLLED_BUILD_ARTIFACT) {
            return "codex/1.4.0-m4-shadow-research-ready".equals(branchName);
        }
        if (buildMode == BuildMode.M6_STAGE_CONTROLLED_BUILD_ARTIFACT) {
            return "codex/1.4.0-m6-research-production-ready".equals(branchName);
        }
        if (buildMode
                == BuildMode.RESEARCH_SELECTION_CONTROLLED_BUILD_ARTIFACT) {
            return Set.of(
                    "codex/1.4.0-v1.0.1-research-selection-usability",
                    "codex/1.4.0-v1.0.2-startup-self-heal-fix",
                    "codex/1.4.0-v1.0.3-research-selection-runtime-fix",
                    "codex/1.4.0-v1.0.7-intraday-research-selection-anchor-fix",
                    "codex/1.4.0-v1.0.9-full-mainboard-universe",
                    "codex/1.4.0-v1.0.11-mainboard-daily-increment",
                    "codex/1.4.0-mainboard-250-session-history-backfill")
                    .contains(branchName);
        }
        return REQUIRED_INTEGRATION_BRANCH.equals(branchName)
                || branchName.startsWith("codex/");
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }
}

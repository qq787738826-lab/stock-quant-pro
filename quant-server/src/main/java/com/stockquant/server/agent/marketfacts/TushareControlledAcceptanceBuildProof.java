package com.stockquant.server.agent.marketfacts;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
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
                gitCommit, gitCommit, REQUIRED_INTEGRATION_BRANCH, true, true,
                Instant.parse("2026-08-01T00:00:00Z"),
                "17-test", MODULE_VERSION, MAVEN_WRAPPER_VERSION,
                BuildMode.CONTROLLED_BUILD_ARTIFACT,
                TushareControlledAcceptanceExecution.EXECUTOR_VERSION,
                TushareControlledAcceptanceExecution.RULE_VERSION);
        return new VerifiedBuildProof(
                gitCommit, gitCommit, REQUIRED_INTEGRATION_BRANCH, true, true,
                artifactSha256, artifactSha256,
                Instant.parse("2026-08-01T00:00:00Z"),
                "17-test", MODULE_VERSION, MAVEN_WRAPPER_VERSION,
                BuildMode.CONTROLLED_BUILD_ARTIFACT,
                TushareControlledAcceptanceExecution.EXECUTOR_VERSION,
                TushareControlledAcceptanceExecution.RULE_VERSION,
                manifest, ProofSource.TEST_ONLY);
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
                    || !gitCommit.equals(manifest.gitCommit())
                    || !remoteGitCommit.equals(manifest.remoteGitCommit())
                    || !branchAllowedForMode(branchName, buildMode)
                    || buildMode == BuildMode.CONTROLLED_BUILD_ARTIFACT
                    && !gitCommit.equals(remoteGitCommit)
                    || !branchName.equals(manifest.branchName())
                    || trackedWorkspaceClean != manifest.trackedWorkspaceClean()
                    || untrackedScopeClean != manifest.untrackedScopeClean()
                    || !buildTime.equals(manifest.buildTime())
                    || !javaVersion.equals(manifest.javaVersion())
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
        PREPARATION_ONLY,
        TEST_ONLY
    }

    public enum BuildMode {
        PREPARATION_ONLY,
        CONTROLLED_BUILD_ARTIFACT
    }

    private static Path currentExecutorArtifact() {
        try {
            Path path = Path.of(TushareControlledAcceptanceExecutor.class
                    .getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)
                    || !path.getFileName().toString().endsWith(".jar")) {
                throw invalid("TUSHARE_CONTROLLED_ACCEPTANCE_RUNNING_ARTIFACT_INVALID");
            }
            return path;
        } catch (URISyntaxException | NullPointerException error) {
            throw invalid("TUSHARE_CONTROLLED_ACCEPTANCE_RUNNING_ARTIFACT_INVALID");
        }
    }

    private static ManifestProof readManifest(Path artifact) {
        try (JarFile jar = new JarFile(artifact.toFile())) {
            if (jar.getManifest() == null) {
                throw invalid("TUSHARE_CONTROLLED_ACCEPTANCE_ARTIFACT_MANIFEST_INVALID");
            }
            Attributes attributes = jar.getManifest().getMainAttributes();
            return new ManifestProof(
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

    private static boolean branchAllowedForMode(String branchName, BuildMode buildMode) {
        if (buildMode == BuildMode.CONTROLLED_BUILD_ARTIFACT) {
            return REQUIRED_INTEGRATION_BRANCH.equals(branchName);
        }
        return REQUIRED_INTEGRATION_BRANCH.equals(branchName)
                || branchName.startsWith("codex/");
    }

    private static IllegalArgumentException invalid(String code) {
        return new IllegalArgumentException(code);
    }
}

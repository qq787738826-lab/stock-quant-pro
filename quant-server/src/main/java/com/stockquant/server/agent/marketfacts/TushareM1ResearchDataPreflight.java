package com.stockquant.server.agent.marketfacts;

import java.nio.file.Path;
import java.time.Clock;

/** Provider-free parser and build binding check used by the Host Broker. */
public final class TushareM1ResearchDataPreflight {
    private static final String ARGUMENT = "--authorization-file=";

    private TushareM1ResearchDataPreflight() {
    }

    public static void main(String[] args) {
        try {
            if (args == null || args.length != 1 || args[0] == null
                    || !args[0].startsWith(ARGUMENT)
                    || args[0].length() == ARGUMENT.length()) {
                throw new IllegalArgumentException(
                        "TUSHARE_M1_PREFLIGHT_ARGUMENT_INVALID");
            }
            Path file = Path.of(args[0].substring(ARGUMENT.length()))
                    .toAbsolutePath().normalize();
            if (file.toString().toLowerCase(java.util.Locale.ROOT)
                    .contains(java.io.File.separator + ".ai"
                            + java.io.File.separator)) {
                throw new IllegalArgumentException(
                        "TUSHARE_M1_PREFLIGHT_PATH_INVALID");
            }
            TushareM1ResearchDataAuthorization authorization =
                    TushareM1ResearchDataAuthorization.load(file);
            authorization.validateAt(Clock.systemUTC());
            var proof = TushareControlledAcceptanceBuildProof
                    .loadCurrentExecutorArtifact(
                            authorization.buildProofPath());
            authorization.validateBuildProof(proof);
            System.out.println("TUSHARE_M1_PREFLIGHT=PASS");
            System.out.println("TUSHARE_M1_GIT_COMMIT="
                    + authorization.gitCommit());
            System.out.println("TUSHARE_M1_ARTIFACT_SHA256="
                    + authorization.artifactSha256());
            System.out.println("TUSHARE_M1_BUILD_PROOF_PATH="
                    + authorization.buildProofPath());
            System.out.println("TUSHARE_M1_MAXIMUM_PROVIDER_REQUESTS="
                    + authorization.maximumProviderRequests());
            System.out.println("TUSHARE_M1_STAGE_PROVIDER_CALLS_BEFORE="
                    + authorization.stageProviderCallsBefore());
        } catch (Throwable error) {
            System.err.println("TUSHARE_M1_PREFLIGHT=FAILED");
            System.err.println("TUSHARE_M1_PREFLIGHT_REASON="
                    + safeCode(error));
            System.exit(20);
        }
    }

    private static String safeCode(Throwable error) {
        for (Throwable value = error; value != null; value = value.getCause()) {
            if (value.getMessage() != null
                    && value.getMessage().matches(
                    "[A-Z][A-Z0-9_]{7,127}")) {
                return value.getMessage();
            }
        }
        return "TUSHARE_M1_PREFLIGHT_FAILED";
    }
}

package com.stockquant.server.agent.marketfacts;

import java.nio.file.Path;
import java.time.Clock;

/** Provider-free parser and build binding check for the one-call probe. */
public final class TushareM1TokenVerificationPreflight {
    private static final String ARGUMENT = "--authorization-file=";

    private TushareM1TokenVerificationPreflight() {
    }

    public static void main(String[] args) {
        try {
            Path file = parse(args);
            TushareM1TokenVerificationAuthorization authorization =
                    TushareM1TokenVerificationAuthorization.load(file);
            authorization.validateAt(Clock.systemUTC());
            var proof = TushareControlledAcceptanceBuildProof
                    .loadCurrentExecutorArtifact(
                            authorization.buildProofPath());
            authorization.validateBuildProof(proof);
            System.out.println(
                    "TUSHARE_M1_TOKEN_VERIFICATION_PREFLIGHT=PASS");
            System.out.println("TUSHARE_M1_TOKEN_VERIFICATION_ID="
                    + authorization.verificationId());
            System.out.println("TUSHARE_M1_TOKEN_VERIFICATION_GIT_COMMIT="
                    + authorization.gitCommit());
            System.out.println("TUSHARE_M1_TOKEN_VERIFICATION_ARTIFACT_SHA256="
                    + authorization.artifactSha256());
            System.out.println("TUSHARE_M1_TOKEN_VERIFICATION_BUILD_PROOF_PATH="
                    + authorization.buildProofPath());
            System.out.println(
                    "TUSHARE_M1_TOKEN_VERIFICATION_MAXIMUM_PROVIDER_REQUESTS=1");
        } catch (Throwable error) {
            System.err.println(
                    "TUSHARE_M1_TOKEN_VERIFICATION_PREFLIGHT=FAILED");
            System.err.println(
                    "TUSHARE_M1_TOKEN_VERIFICATION_PREFLIGHT_REASON="
                            + safeCode(error));
            System.exit(20);
        }
    }

    private static Path parse(String[] args) {
        if (args == null || args.length != 1 || args[0] == null
                || !args[0].startsWith(ARGUMENT)
                || args[0].length() == ARGUMENT.length()) {
            throw new IllegalArgumentException(
                    "TUSHARE_M1_TOKEN_VERIFICATION_PREFLIGHT_ARGUMENT_INVALID");
        }
        Path path = Path.of(args[0].substring(ARGUMENT.length()))
                .toAbsolutePath().normalize();
        for (Path segment : path) {
            if (".ai".equalsIgnoreCase(segment.toString())) {
                throw new IllegalArgumentException(
                        "TUSHARE_M1_TOKEN_VERIFICATION_PREFLIGHT_PATH_INVALID");
            }
        }
        return path;
    }

    private static String safeCode(Throwable error) {
        for (Throwable value = error; value != null; value = value.getCause()) {
            if (value.getMessage() != null && value.getMessage().matches(
                    "[A-Z][A-Z0-9_]{7,127}")) {
                return value.getMessage();
            }
        }
        return "TUSHARE_M1_TOKEN_VERIFICATION_PREFLIGHT_FAILED";
    }
}

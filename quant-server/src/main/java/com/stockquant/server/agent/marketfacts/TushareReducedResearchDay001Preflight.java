package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.TushareControlledAcceptanceBuildProof.VerifiedBuildProof;

import java.nio.file.Path;
import java.time.Clock;

/** Formal parser/build preflight. It never reads secrets or contacts a provider. */
public final class TushareReducedResearchDay001Preflight {
    private static final String AUTHORIZATION_ARGUMENT = "--authorization-file=";

    private TushareReducedResearchDay001Preflight() {
    }

    public static void main(String[] args) {
        System.exit(run(args, Clock.systemUTC()));
    }

    static int run(String[] args, Clock clock) {
        try {
            Path authorizationFile = parse(args);
            TushareReducedResearchDay001Authorization authorization =
                    TushareReducedResearchDay001Authorization.load(
                            authorizationFile);
            if (authorization.e2eDryRun()) {
                throw new IllegalArgumentException(
                        "TUSHARE_REDUCED_RESEARCH_USER_APPROVAL_REQUIRED");
            }
            authorization.validateAt(clock);
            VerifiedBuildProof proof = TushareControlledAcceptanceBuildProof
                    .loadCurrentExecutorArtifact(
                            authorization.buildProofPath());
            authorization.validateBuildProof(proof);
            System.out.println(
                    "TUSHARE_REDUCED_RESEARCH_DAY001_PREFLIGHT=PASS");
            System.out.println("TUSHARE_REDUCED_RESEARCH_RUN_ID="
                    + authorization.runId());
            System.out.println("TUSHARE_REDUCED_RESEARCH_GIT_COMMIT="
                    + authorization.gitCommit());
            System.out.println("TUSHARE_REDUCED_RESEARCH_ARTIFACT_SHA256="
                    + authorization.artifactSha256());
            System.out.println("TUSHARE_REDUCED_RESEARCH_BUILD_PROOF_PATH="
                    + authorization.buildProofPath().toAbsolutePath()
                    .normalize());
            return 0;
        } catch (Throwable error) {
            String code = safeCode(error);
            System.err.println(
                    "TUSHARE_REDUCED_RESEARCH_PREFLIGHT_REASON=" + code);
            return 20;
        }
    }

    private static Path parse(String[] args) {
        if (args == null || args.length != 1 || args[0] == null
                || !args[0].startsWith(AUTHORIZATION_ARGUMENT)
                || args[0].length() == AUTHORIZATION_ARGUMENT.length()) {
            throw new IllegalArgumentException(
                    "TUSHARE_REDUCED_RESEARCH_PREFLIGHT_ARGUMENT_INVALID");
        }
        Path path = Path.of(args[0].substring(AUTHORIZATION_ARGUMENT.length()))
                .toAbsolutePath().normalize();
        for (Path segment : path) {
            if (".ai".equalsIgnoreCase(segment.toString())) {
                throw new IllegalArgumentException(
                        "TUSHARE_REDUCED_RESEARCH_PREFLIGHT_ARGUMENT_INVALID");
            }
        }
        return path;
    }

    private static String safeCode(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null
                    && message.matches("[A-Z][A-Z0-9_]{7,127}")) {
                return message;
            }
            current = current.getCause();
        }
        return "TUSHARE_REDUCED_RESEARCH_PREFLIGHT_FAILED";
    }
}

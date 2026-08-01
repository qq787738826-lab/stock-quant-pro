package com.stockquant.server.agent.marketfacts;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;

/** Captures and audits actual controlled-execution output without persisting it. */
public final class TushareControlledAcceptanceOutputAudit {
    private static final ReentrantLock CAPTURE_LOCK = new ReentrantLock();

    private TushareControlledAcceptanceOutputAudit() {
    }

    static <T> Captured<T> capture(
            List<SensitiveMaterial> sensitiveMaterials,
            Callable<T> action
    ) throws Exception {
        Objects.requireNonNull(action, "action");
        List<SensitiveMaterial> materials = List.copyOf(
                Objects.requireNonNull(sensitiveMaterials, "sensitiveMaterials"));
        CAPTURE_LOCK.lockInterruptibly();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        Throwable failure = null;
        T result = null;
        boolean complete = false;
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            appender.setContext(root.getLoggerContext());
            appender.start();
            root.addAppender(appender);
            System.setOut(out);
            System.setErr(err);
            try {
                result = action.call();
            } catch (Throwable error) {
                failure = error;
            } finally {
                out.flush();
                err.flush();
                complete = true;
            }
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            root.detachAppender(appender);
            appender.stop();
            CAPTURE_LOCK.unlock();
        }
        List<CapturedText> texts = new ArrayList<>();
        texts.add(new CapturedText("STDOUT", stdout.toString(StandardCharsets.UTF_8)));
        texts.add(new CapturedText("STDERR", stderr.toString(StandardCharsets.UTF_8)));
        for (ILoggingEvent event : appender.list) {
            texts.add(new CapturedText("LOG", event.getFormattedMessage()));
            if (event.getThrowableProxy() != null) {
                texts.add(new CapturedText("LOG_EXCEPTION",
                        event.getThrowableProxy().getMessage()));
            }
        }
        appendThrowable(texts, failure);
        AuditResult audit = audit(texts, materials, complete);
        if (failure != null) {
            if (failure instanceof Exception exception) {
                throw new CapturedExecutionException(exception, audit);
            }
            throw new CapturedExecutionException(
                    new IllegalStateException("CONTROLLED_EXECUTION_FAILED", failure), audit);
        }
        return new Captured<>(result, audit);
    }

    static AuditResult audit(
            List<CapturedText> capturedTexts,
            List<SensitiveMaterial> sensitiveMaterials,
            boolean captureComplete
    ) {
        List<AuditHit> hits = new ArrayList<>();
        for (CapturedText captured : capturedTexts) {
            String text = captured.value() == null ? "" : captured.value();
            String lower = text.toLowerCase(Locale.ROOT);
            detectPattern(hits, captured.channel(), lower, "authorization:", HitCategory.AUTHORIZATION_HEADER);
            detectPattern(hits, captured.channel(), lower, "bearer ", HitCategory.BEARER_VALUE);
            detectPattern(hits, captured.channel(), lower, "password=", HitCategory.JDBC_CREDENTIAL_PARAMETER);
            detectPattern(hits, captured.channel(), lower, "user=", HitCategory.JDBC_CREDENTIAL_PARAMETER);
            detectPattern(hits, captured.channel(), lower, "jdbc:", HitCategory.JDBC_URL);
            detectPattern(hits, captured.channel(), lower, "\"data\":[", HitCategory.PROVIDER_PAYLOAD);
            detectPattern(hits, captured.channel(), lower, "\"items\":[", HitCategory.PROVIDER_PAYLOAD);
            for (SensitiveMaterial material : sensitiveMaterials) {
                for (Variant variant : material.variants()) {
                    int index = text.indexOf(variant.value());
                    if (index >= 0) {
                        hits.add(new AuditHit(captured.channel(), variant.category(), index));
                    }
                }
            }
        }
        return new AuditResult(captureComplete, hits.isEmpty(), List.copyOf(hits));
    }

    private static void appendThrowable(List<CapturedText> texts, Throwable failure) {
        Throwable cursor = failure;
        int depth = 0;
        while (cursor != null && depth++ < 16) {
            texts.add(new CapturedText("EXCEPTION", cursor.getMessage()));
            cursor = cursor.getCause();
        }
    }

    private static void detectPattern(
            List<AuditHit> hits,
            String channel,
            String text,
            String pattern,
            HitCategory category
    ) {
        int index = text.indexOf(pattern);
        if (index >= 0) {
            hits.add(new AuditHit(channel, category, index));
        }
    }

    static final class SensitiveMaterial {
        private final List<Variant> variants;

        private SensitiveMaterial(List<Variant> variants) {
            this.variants = List.copyOf(variants);
        }

        static SensitiveMaterial register(String secret) {
            if (secret == null || secret.length() < 8) {
                throw new IllegalArgumentException("CONTROLLED_ACCEPTANCE_SENSITIVE_VALUE_INVALID");
            }
            List<Variant> variants = new ArrayList<>();
            variants.add(new Variant(secret, HitCategory.SECRET_EXACT));
            variants.add(new Variant(secret.substring(0, Math.min(6, secret.length())),
                    HitCategory.SECRET_PREFIX));
            variants.add(new Variant(secret.substring(Math.max(0, secret.length() - 6)),
                    HitCategory.SECRET_SUFFIX));
            variants.add(new Variant(sha256(secret), HitCategory.RECOVERABLE_SECRET_DERIVATIVE));
            variants.add(new Variant(Base64.getEncoder().encodeToString(
                    secret.getBytes(StandardCharsets.UTF_8)),
                    HitCategory.RECOVERABLE_SECRET_DERIVATIVE));
            return new SensitiveMaterial(variants);
        }

        List<Variant> variants() {
            return variants;
        }

        @Override
        public String toString() {
            return "SensitiveMaterial[REDACTED]";
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    record Variant(String value, HitCategory category) {
    }

    record CapturedText(String channel, String value) {
        CapturedText {
            channel = TushareControlledAcceptanceExecution.safeText(channel);
        }
    }

    public record AuditHit(String channel, HitCategory category, int position) {
        public AuditHit {
            channel = TushareControlledAcceptanceExecution.safeText(channel);
            Objects.requireNonNull(category, "category");
            if (position < 0) {
                throw new IllegalArgumentException("CONTROLLED_ACCEPTANCE_AUDIT_POSITION_INVALID");
            }
        }
    }

    public record AuditResult(boolean captureComplete, boolean clean, List<AuditHit> hits) {
        public AuditResult {
            hits = List.copyOf(Objects.requireNonNull(hits, "hits"));
            if (clean != (captureComplete && hits.isEmpty())) {
                throw new IllegalArgumentException("CONTROLLED_ACCEPTANCE_AUDIT_RESULT_INVALID");
            }
        }
    }

    record Captured<T>(T value, AuditResult auditResult) {
    }

    static final class CapturedExecutionException extends Exception {
        private final AuditResult auditResult;

        CapturedExecutionException(Exception cause, AuditResult auditResult) {
            super("CONTROLLED_ACCEPTANCE_CAPTURED_EXECUTION_FAILED", cause);
            this.auditResult = auditResult;
        }

        AuditResult auditResult() {
            return auditResult;
        }
    }

    public enum HitCategory {
        SECRET_EXACT,
        SECRET_PREFIX,
        SECRET_SUFFIX,
        RECOVERABLE_SECRET_DERIVATIVE,
        AUTHORIZATION_HEADER,
        BEARER_VALUE,
        JDBC_CREDENTIAL_PARAMETER,
        JDBC_URL,
        PROVIDER_PAYLOAD
    }
}

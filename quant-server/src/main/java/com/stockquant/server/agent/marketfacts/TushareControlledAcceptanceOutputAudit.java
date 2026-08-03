package com.stockquant.server.agent.marketfacts;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;

/** Captures and audits controlled-execution output without persisting content. */
public final class TushareControlledAcceptanceOutputAudit {
    private static final ReentrantLock CAPTURE_LOCK = new ReentrantLock();

    private TushareControlledAcceptanceOutputAudit() {
    }

    static <T> Captured<T> capture(
            List<SensitiveMaterial> sensitiveMaterials,
            Callable<T> action
    ) throws Exception {
        List<SensitiveMaterial> frozen = List.copyOf(
                Objects.requireNonNull(sensitiveMaterials, "sensitiveMaterials"));
        return captureAfterRegistration(() -> frozen, action);
    }

    static <T> Captured<T> captureAfterRegistration(
            Callable<List<SensitiveMaterial>> sensitiveMaterialSource,
            Callable<T> action
    ) throws Exception {
        Objects.requireNonNull(sensitiveMaterialSource, "sensitiveMaterialSource");
        Objects.requireNonNull(action, "action");
        return captureDynamic(registry -> {
            registry.registerAll(List.copyOf(Objects.requireNonNull(
                    sensitiveMaterialSource.call(), "sensitiveMaterials")));
            return action.call();
        }, RegistryRequirement.NONE);
    }

    static <T> Captured<T> captureControlledProcess(
            DynamicAction<T> action
    ) throws Exception {
        return captureDynamic(Objects.requireNonNull(action, "action"),
                RegistryRequirement.CONTROLLED_ACCEPTANCE);
    }

    static <T> Captured<T> captureDatabasePreparationProcess(
            DynamicAction<T> action
    ) throws Exception {
        return captureDynamic(Objects.requireNonNull(action, "action"),
                RegistryRequirement.DATABASE_PREPARATION);
    }

    private static <T> Captured<T> captureDynamic(
            DynamicAction<T> action,
            RegistryRequirement registryRequirement
    ) throws Exception {
        SensitiveRegistry registry = new SensitiveRegistry();
        CAPTURE_LOCK.lockInterruptibly();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        LoggerContext context = null;
        Logger root = null;
        ListAppender<ILoggingEvent> captureAppender = null;
        List<LoggerState> loggerStates = List.of();
        Throwable failure = null;
        T result = null;
        boolean complete = false;
        AuditResult audit = null;
        Throwable restoreFailure = null;
        try {
            context = requireLogbackContext();
            root = context.getLogger(Logger.ROOT_LOGGER_NAME);
            captureAppender = new ListAppender<>();
            loggerStates = snapshotLoggerTopology(context);
            try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
                 PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
                captureAppender.setContext(context);
                captureAppender.start();
                isolateLogback(loggerStates, root, captureAppender);
                System.setOut(out);
                System.setErr(err);
                try {
                    result = action.call(registry);
                    registryRequirement.validate(registry);
                } catch (Throwable error) {
                    failure = error;
                } finally {
                    registry.close();
                    out.flush();
                    err.flush();
                    complete = topologyStillIsolated(context, root, captureAppender);
                }
            }

            // The final flush, captured-event expansion and sensitive scan all
            // happen while stdout/stderr and Logback remain isolated. No
            // controlled component can emit output after this audit point.
            List<CapturedText> texts = new ArrayList<>();
            texts.add(new CapturedText("STDOUT",
                    stdout.toString(StandardCharsets.UTF_8)));
            texts.add(new CapturedText("STDERR",
                    stderr.toString(StandardCharsets.UTF_8)));
            for (ILoggingEvent event : captureAppender.list) {
                texts.add(new CapturedText("LOG", event.getFormattedMessage()));
                appendThrowableProxy(texts, event.getThrowableProxy(),
                    Collections.newSetFromMap(new IdentityHashMap<>()));
            }
            appendThrowable(texts, failure,
                    Collections.newSetFromMap(new IdentityHashMap<>()));
            try {
                audit = audit(texts, registry.materials(), complete);
            } catch (RuntimeException auditFailure) {
                audit = failedAudit();
                failure = combine(failure, auditFailure);
            }
        } catch (Throwable auditInfrastructureFailure) {
            registry.close();
            complete = false;
            audit = failedAudit();
            failure = combine(failure, auditInfrastructureFailure);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            try {
                if (context != null && captureAppender != null) {
                    restoreLogback(context, loggerStates, captureAppender);
                }
            } catch (Throwable error) {
                restoreFailure = error;
            } finally {
                CAPTURE_LOCK.unlock();
            }
        }
        if (restoreFailure != null) {
            audit = failedAudit();
            failure = combine(failure, restoreFailure);
        }
        if (failure != null) {
            Exception safeFailure = failure instanceof Exception exception
                    ? exception : new IllegalStateException(
                    "CONTROLLED_EXECUTION_FAILED", failure);
            throw new CapturedExecutionException(safeFailure, audit);
        }
        return new Captured<>(result, audit);
    }

    private static AuditResult failedAudit() {
        return new AuditResult(false, false,
                List.of(new AuditHit("AUDIT", HitCategory.AUDIT_FAILURE, 0)));
    }

    private static Throwable combine(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        if (first != next) {
            first.addSuppressed(next);
        }
        return first;
    }

    @FunctionalInterface
    interface DynamicAction<T> {
        T call(SensitiveRegistry registry) throws Exception;
    }

    enum SensitiveKind {
        DATABASE_PASSWORD,
        TUSHARE_TOKEN,
        ADMINISTRATOR_DATABASE_PASSWORD,
        DEDICATED_BOOTSTRAP_PASSWORD,
        DEDICATED_DATABASE_PASSWORD
    }

    static final class SensitiveRegistry {
        private final List<SensitiveMaterial> materials = new ArrayList<>();
        private final List<SensitiveKind> kinds = new ArrayList<>();
        private boolean active = true;

        void register(SensitiveKind kind, char[] secret) {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(secret, "secret");
            if (!active || kinds.contains(kind)) {
                throw new IllegalStateException(
                        "TUSHARE_CONTROLLED_ACCEPTANCE_SENSITIVE_REGISTRY_INVALID");
            }
            String value = new String(secret);
            materials.add(SensitiveMaterial.register(value));
            kinds.add(kind);
        }

        private void registerAll(List<SensitiveMaterial> values) {
            if (!active || values.isEmpty()) {
                throw new IllegalStateException(
                        "TUSHARE_CONTROLLED_ACCEPTANCE_SENSITIVE_REGISTRY_REQUIRED");
            }
            materials.addAll(values);
        }

        private void requireCompleteControlledRegistration() {
            if (!kinds.equals(List.of(
                    SensitiveKind.DATABASE_PASSWORD,
                    SensitiveKind.TUSHARE_TOKEN))) {
                throw new IllegalStateException(
                        "TUSHARE_CONTROLLED_ACCEPTANCE_SENSITIVE_REGISTRY_INCOMPLETE");
            }
        }

        private void requireCompleteDatabasePreparationRegistration() {
            if ((databasePreparationSecretsRequired
                    && !kinds.equals(List.of(
                    SensitiveKind.ADMINISTRATOR_DATABASE_PASSWORD,
                    SensitiveKind.DEDICATED_BOOTSTRAP_PASSWORD,
                    SensitiveKind.DEDICATED_DATABASE_PASSWORD)))
                    || (!databasePreparationSecretsRequired
                    && !kinds.isEmpty())) {
                throw new IllegalStateException(
                        "TUSHARE_DATABASE_PREPARATION_SENSITIVE_REGISTRY_INCOMPLETE");
            }
        }

        void requireDatabasePreparationSecrets() {
            if (!active || databasePreparationSecretsRequired
                    || !kinds.isEmpty()) {
                throw new IllegalStateException(
                        "TUSHARE_DATABASE_PREPARATION_SENSITIVE_REGISTRY_INVALID");
            }
            databasePreparationSecretsRequired = true;
        }

        boolean active() {
            return active;
        }

        List<SensitiveKind> registeredKinds() {
            return List.copyOf(kinds);
        }

        private List<SensitiveMaterial> materials() {
            return List.copyOf(materials);
        }

        private void close() {
            active = false;
        }

        private boolean databasePreparationSecretsRequired;
    }

    private enum RegistryRequirement {
        NONE {
            @Override
            void validate(SensitiveRegistry registry) {
                if (registry.materials().isEmpty()) {
                    throw new IllegalStateException(
                            "TUSHARE_CONTROLLED_ACCEPTANCE_SENSITIVE_REGISTRY_REQUIRED");
                }
            }
        },
        CONTROLLED_ACCEPTANCE {
            @Override
            void validate(SensitiveRegistry registry) {
                NONE.validate(registry);
                registry.requireCompleteControlledRegistration();
            }
        },
        DATABASE_PREPARATION {
            @Override
            void validate(SensitiveRegistry registry) {
                registry.requireCompleteDatabasePreparationRegistration();
            }
        };

        abstract void validate(SensitiveRegistry registry);
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
            detectPattern(hits, captured.channel(), lower,
                    "authorization:", HitCategory.AUTHORIZATION_HEADER);
            detectPattern(hits, captured.channel(), lower,
                    "bearer ", HitCategory.BEARER_VALUE);
            detectPattern(hits, captured.channel(), lower,
                    "password=", HitCategory.JDBC_CREDENTIAL_PARAMETER);
            detectPattern(hits, captured.channel(), lower,
                    "password:", HitCategory.JDBC_CREDENTIAL_PARAMETER);
            detectPattern(hits, captured.channel(), lower,
                    "user=", HitCategory.JDBC_CREDENTIAL_PARAMETER);
            detectPattern(hits, captured.channel(), lower,
                    "jdbc:", HitCategory.JDBC_URL);
            detectPattern(hits, captured.channel(), lower,
                    "token=", HitCategory.TOKEN_PARAMETER);
            detectPattern(hits, captured.channel(), lower,
                    "token:", HitCategory.TOKEN_PARAMETER);
            detectPattern(hits, captured.channel(), lower,
                    "tushare_token=", HitCategory.ENVIRONMENT_SECRET);
            detectPattern(hits, captured.channel(), lower,
                    "tushare_token:", HitCategory.ENVIRONMENT_SECRET);
            detectPattern(hits, captured.channel(), lower,
                    "\"data\":[", HitCategory.PROVIDER_PAYLOAD);
            detectPattern(hits, captured.channel(), lower,
                    "\"items\":[", HitCategory.PROVIDER_PAYLOAD);
            for (SensitiveMaterial material : sensitiveMaterials) {
                for (Variant variant : material.variants()) {
                    int index = text.indexOf(variant.value());
                    if (index >= 0) {
                        hits.add(new AuditHit(
                                captured.channel(), variant.category(), index));
                    }
                }
            }
        }
        return new AuditResult(captureComplete, captureComplete && hits.isEmpty(),
                List.copyOf(hits));
    }

    private static LoggerContext requireLogbackContext() {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            throw new IllegalStateException(
                    "TUSHARE_CONTROLLED_ACCEPTANCE_LOGBACK_REQUIRED");
        }
        return context;
    }

    private static List<LoggerState> snapshotLoggerTopology(LoggerContext context) {
        List<LoggerState> states = new ArrayList<>();
        for (Logger logger : context.getLoggerList()) {
            List<Appender<ILoggingEvent>> appenders = new ArrayList<>();
            Iterator<Appender<ILoggingEvent>> iterator = logger.iteratorForAppenders();
            while (iterator.hasNext()) {
                appenders.add(iterator.next());
            }
            states.add(new LoggerState(logger, logger.isAdditive(), appenders));
        }
        return List.copyOf(states);
    }

    private static void isolateLogback(
            List<LoggerState> states,
            Logger root,
            ListAppender<ILoggingEvent> captureAppender
    ) {
        for (LoggerState state : states) {
            state.appenders().forEach(state.logger()::detachAppender);
            if (state.logger() != root) {
                state.logger().setAdditive(true);
            }
        }
        root.addAppender(captureAppender);
    }

    private static boolean topologyStillIsolated(
            LoggerContext context,
            Logger root,
            ListAppender<ILoggingEvent> captureAppender
    ) {
        for (Logger logger : context.getLoggerList()) {
            Iterator<Appender<ILoggingEvent>> iterator = logger.iteratorForAppenders();
            while (iterator.hasNext()) {
                if (iterator.next() != captureAppender || logger != root) {
                    return false;
                }
            }
            if (logger != root && !logger.isAdditive()) {
                return false;
            }
        }
        return true;
    }

    private static void restoreLogback(
            LoggerContext context,
            List<LoggerState> states,
            ListAppender<ILoggingEvent> captureAppender
    ) {
        for (Logger logger : context.getLoggerList()) {
            logger.detachAppender(captureAppender);
        }
        for (LoggerState state : states) {
            state.logger().setAdditive(state.additive());
            state.appenders().forEach(state.logger()::addAppender);
        }
        captureAppender.stop();
    }

    private static void appendThrowable(
            List<CapturedText> texts,
            Throwable failure,
            Set<Throwable> visited
    ) {
        if (failure == null || !visited.add(failure) || visited.size() > 64) {
            return;
        }
        texts.add(new CapturedText("EXCEPTION", failure.getMessage()));
        appendThrowable(texts, failure.getCause(), visited);
        for (Throwable suppressed : failure.getSuppressed()) {
            appendThrowable(texts, suppressed, visited);
        }
    }

    private static void appendThrowableProxy(
            List<CapturedText> texts,
            IThrowableProxy proxy,
            Set<IThrowableProxy> visited
    ) {
        if (proxy == null || !visited.add(proxy) || visited.size() > 64) {
            return;
        }
        texts.add(new CapturedText("LOG_EXCEPTION", proxy.getMessage()));
        appendThrowableProxy(texts, proxy.getCause(), visited);
        for (IThrowableProxy suppressed : proxy.getSuppressed()) {
            appendThrowableProxy(texts, suppressed, visited);
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
                throw new IllegalArgumentException(
                        "CONTROLLED_ACCEPTANCE_SENSITIVE_VALUE_INVALID");
            }
            List<Variant> variants = new ArrayList<>();
            variants.add(new Variant(secret, HitCategory.SECRET_EXACT));
            variants.add(new Variant(secret.substring(0, Math.min(6, secret.length())),
                    HitCategory.SECRET_PREFIX));
            variants.add(new Variant(secret.substring(Math.max(0, secret.length() - 6)),
                    HitCategory.SECRET_SUFFIX));
            variants.add(new Variant(sha256(secret),
                    HitCategory.RECOVERABLE_SECRET_DERIVATIVE));
            variants.add(new Variant(Base64.getEncoder().encodeToString(
                    secret.getBytes(StandardCharsets.UTF_8)),
                    HitCategory.RECOVERABLE_SECRET_DERIVATIVE));
            variants.add(new Variant(Base64.getUrlEncoder().withoutPadding().encodeToString(
                    secret.getBytes(StandardCharsets.UTF_8)),
                    HitCategory.RECOVERABLE_SECRET_DERIVATIVE));
            variants.add(new Variant(URLEncoder.encode(secret, StandardCharsets.UTF_8),
                    HitCategory.URL_ENCODED_SECRET));
            return new SensitiveMaterial(variants.stream().distinct().toList());
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

    private record LoggerState(
            Logger logger,
            boolean additive,
            List<Appender<ILoggingEvent>> appenders
    ) {
        private LoggerState {
            appenders = List.copyOf(appenders);
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
                throw new IllegalArgumentException(
                        "CONTROLLED_ACCEPTANCE_AUDIT_POSITION_INVALID");
            }
        }
    }

    public record AuditResult(boolean captureComplete, boolean clean, List<AuditHit> hits) {
        public AuditResult {
            hits = List.copyOf(Objects.requireNonNull(hits, "hits"));
            if (clean != (captureComplete && hits.isEmpty())) {
                throw new IllegalArgumentException(
                        "CONTROLLED_ACCEPTANCE_AUDIT_RESULT_INVALID");
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
        URL_ENCODED_SECRET,
        AUTHORIZATION_HEADER,
        BEARER_VALUE,
        TOKEN_PARAMETER,
        ENVIRONMENT_SECRET,
        JDBC_CREDENTIAL_PARAMETER,
        JDBC_URL,
        PROVIDER_PAYLOAD,
        AUDIT_FAILURE
    }
}

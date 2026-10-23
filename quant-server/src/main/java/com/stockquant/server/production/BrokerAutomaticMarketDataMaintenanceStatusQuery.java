package com.stockquant.server.production;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.stockquant.server.production.AutomaticMarketDataMaintenanceStatusQuery.Status;

/** Reads only sanitized Broker state for the deterministic maintenance ID. */
@Component
public final class BrokerAutomaticMarketDataMaintenanceStatusQuery
        implements AutomaticMarketDataMaintenanceStatusQuery {
    private static final long MAXIMUM_MAINTENANCE_BYTES = 1_048_576;
    private static final int MAXIMUM_DATE_COUNT = 366;

    private final ObjectMapper mapper;
    private final MarketDataReadyGate readyGate;
    private final Path brokerBase;

    @Autowired
    public BrokerAutomaticMarketDataMaintenanceStatusQuery(
            ObjectMapper mapper,
            MarketDataReadyGate readyGate
    ) {
        this(mapper, readyGate, repositoryRoot().resolve(
                "quant-server/target/stock-quant-host-broker"));
    }

    BrokerAutomaticMarketDataMaintenanceStatusQuery(
            ObjectMapper mapper,
            MarketDataReadyGate readyGate,
            Path brokerBase
    ) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.readyGate = Objects.requireNonNull(readyGate, "readyGate");
        this.brokerBase = Objects.requireNonNull(brokerBase, "brokerBase")
                .toAbsolutePath().normalize();
    }

    @Override
    public Snapshot query(LocalDate tradeDate) {
        Objects.requireNonNull(tradeDate, "tradeDate");
        MarketDataReadyGate.ReadyResult ready = readyGate.evaluate(tradeDate);
        String requestId = ready.requestId();
        Status status = status(ready, requestId);
        String reason = status == Status.FAILED
                && "AUTOMATIC_MARKET_DATA_MAINTENANCE_NOT_RUN".equals(
                ready.reasonCode())
                ? "AUTOMATIC_MARKET_DATA_MAINTENANCE_BROKER_REJECTED"
                : ready.reasonCode();
        Details details = details(status, reason, requestId);
        if (!details.within(tradeDate)) {
            details = Details.empty();
        }
        return new Snapshot(status, requestId, tradeDate,
                details.latestCommonCompletedOpenTradeDate(), reason,
                ready.ready(),
                details.latestCompleteTradeDate(),
                details.latestCommonCompletedOpenTradeDate(),
                details.missingTradeDates(), details.partialTradeDates(),
                details.providerCallCount());
    }

    private Status status(
            MarketDataReadyGate.ReadyResult ready,
            String requestId
    ) {
        if (ready.ready()) {
            return Status.SUCCEEDED;
        }
        return switch (ready.reasonCode()) {
            case "AUTOMATIC_MARKET_DATA_MAINTENANCE_IN_PROGRESS" ->
                    stateExists(requestId, "processing")
                            || stateExists(requestId, "processed")
                            ? Status.IN_PROGRESS : Status.SUBMITTED;
            case "AUTOMATIC_MARKET_DATA_MAINTENANCE_RESULT_MISSING" ->
                    Status.IN_PROGRESS;
            case "AUTOMATIC_MARKET_DATA_MAINTENANCE_NOT_RUN" ->
                    stateExists(requestId, "rejected")
                            ? Status.FAILED : Status.NOT_RUN;
            default -> Status.FAILED;
        };
    }

    private boolean stateExists(String requestId, String state) {
        Path path = brokerBase.resolve("requests").resolve(
                requestId + '.' + state + ".properties")
                .toAbsolutePath().normalize();
        return path.startsWith(brokerBase) && Files.isRegularFile(path);
    }

    private Details details(
            Status status,
            String reason,
            String requestId
    ) {
        if (status == Status.NOT_RUN || status == Status.SUBMITTED
                || status == Status.IN_PROGRESS
                || "AUTOMATIC_MARKET_DATA_MAINTENANCE_RESULT_INVALID"
                .equals(reason)) {
            return Details.empty();
        }
        Path path = brokerBase.resolve("results").resolve(requestId
                + ".automatic-daily-market-data-maintenance.json")
                .toAbsolutePath().normalize();
        try {
            if (!path.startsWith(brokerBase) || !Files.isRegularFile(path)
                    || Files.size(path) < 2
                    || Files.size(path) > MAXIMUM_MAINTENANCE_BYTES) {
                return Details.empty();
            }
            JsonNode result = mapper.readTree(path.toFile());
            return new Details(
                    date(result.path("finalLatestCompleteTradeDate")),
                    date(result.path(
                            "finalLatestCommonCompletedOpenTradeDate")),
                    dates(result.path("finalMissingTradeDates")),
                    dates(result.path("finalPartialTradeDates")),
                    count(result.path("tushareProviderCallCount")));
        } catch (IOException | RuntimeException error) {
            return Details.empty();
        }
    }

    private static LocalDate date(JsonNode value) {
        if (!value.isTextual()) return null;
        try {
            return LocalDate.parse(value.textValue());
        } catch (DateTimeException error) {
            return null;
        }
    }

    private static List<LocalDate> dates(JsonNode value) {
        if (!value.isArray() || value.size() > MAXIMUM_DATE_COUNT) return null;
        List<LocalDate> dates = new ArrayList<>();
        for (JsonNode item : value) {
            LocalDate date = date(item);
            if (date == null) return null;
            dates.add(date);
        }
        return List.copyOf(dates);
    }

    private static Integer count(JsonNode value) {
        if (!value.canConvertToInt()) return null;
        int count = value.intValue();
        return count >= 0 ? count : null;
    }

    private static Path repositoryRoot() {
        Path value = Path.of("").toAbsolutePath().normalize();
        for (int depth = 0; value != null && depth < 5;
             depth++, value = value.getParent()) {
            if (Files.exists(value.resolve(".git"))
                    && Files.isDirectory(value.resolve("quant-server"))) {
                return value;
            }
        }
        throw new IllegalStateException(
                "AUTOMATIC_MARKET_DATA_MAINTENANCE_REPOSITORY_INVALID");
    }

    private record Details(
            LocalDate latestCompleteTradeDate,
            LocalDate latestCommonCompletedOpenTradeDate,
            List<LocalDate> missingTradeDates,
            List<LocalDate> partialTradeDates,
            Integer providerCallCount
    ) {
        private static Details empty() {
            return new Details(null, null, null, null, null);
        }

        private boolean within(LocalDate target) {
            return notAfter(latestCompleteTradeDate, target)
                    && notAfter(latestCommonCompletedOpenTradeDate, target)
                    && allNotAfter(missingTradeDates, target)
                    && allNotAfter(partialTradeDates, target);
        }

        private static boolean notAfter(LocalDate value, LocalDate target) {
            return value == null || !value.isAfter(target);
        }

        private static boolean allNotAfter(
                List<LocalDate> values,
                LocalDate target
        ) {
            return values == null || values.stream()
                    .noneMatch(value -> value.isAfter(target));
        }
    }
}

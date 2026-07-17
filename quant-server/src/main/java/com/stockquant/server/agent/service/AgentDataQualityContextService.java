package com.stockquant.server.agent.service;

import com.stockquant.server.agent.repository.AgentContextReadRepository.DailyBarRecord;
import com.stockquant.server.agent.repository.AgentContextReadRepository.SecurityRecord;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

@Service
public class AgentDataQualityContextService {

    public DataQualityFacts analyze(
            Optional<SecurityRecord> security,
            List<DailyBarRecord> dailyBars,
            List<String> adjustTypes,
            LocalDate requestedTradeDate
    ) {
        LocalDate effectiveTradeDate = dailyBars.isEmpty()
                ? null : dailyBars.get(dailyBars.size() - 1).tradeDate();
        List<DailyBarRecord> invalidBars = dailyBars.stream()
                .filter(bar -> !AgentTechnicalMetricsService.isValid(bar))
                .toList();
        List<LocalDate> invalidBarDates = invalidBars.stream()
                .map(DailyBarRecord::tradeDate)
                .distinct()
                .sorted()
                .toList();
        TreeSet<String> missingSecurityFields = new TreeSet<>();
        security.ifPresent(value -> {
            addMissing(missingSecurityFields, "dataSource", value.dataSource());
            addMissing(missingSecurityFields, "industry", value.industry());
            if (value.listDate() == null) {
                missingSecurityFields.add("listDate");
            }
            addMissing(missingSecurityFields, "name", value.name());
        });

        return new DataQualityFacts(
                security.isPresent(),
                security.map(AgentDataQualityContextService::placeholderSuspected).orElse(false),
                security.map(AgentDataQualityContextService::sourceKnown).orElse(false),
                false,
                dailyBars.size(),
                AgentTechnicalMetricsService.REQUIRED_BARS,
                requestedTradeDate.equals(effectiveTradeDate),
                requestedTradeDate,
                effectiveTradeDate,
                effectiveTradeDate == null ? null : ChronoUnit.DAYS.between(effectiveTradeDate, requestedTradeDate),
                false,
                (int) dailyBars.stream().filter(bar -> bar.amount() == null).count(),
                (int) dailyBars.stream().filter(bar -> bar.turnoverRate() == null).count(),
                invalidBars.size(),
                invalidBarDates,
                maximumObservedNaturalDayGap(dailyBars),
                "PRIMARY_KEY_SYMBOL_TRADE_DATE_ADJUST_TYPE",
                false,
                new TreeSet<>(adjustTypes).stream().toList(),
                new ArrayList<>(missingSecurityFields)
        );
    }

    public static boolean placeholderSuspected(SecurityRecord value) {
        return blank(value.name()) || value.symbol().equals(value.name()) || !sourceKnown(value);
    }

    public static boolean sourceKnown(SecurityRecord value) {
        return !blank(value.dataSource());
    }

    private static Long maximumObservedNaturalDayGap(List<DailyBarRecord> dailyBars) {
        if (dailyBars.size() < 2) {
            return null;
        }
        long maximum = 0;
        for (int index = 1; index < dailyBars.size(); index++) {
            maximum = Math.max(maximum, ChronoUnit.DAYS.between(
                    dailyBars.get(index - 1).tradeDate(), dailyBars.get(index).tradeDate()));
        }
        return maximum;
    }

    private static void addMissing(TreeSet<String> missing, String field, String value) {
        if (blank(value)) {
            missing.add(field);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record DataQualityFacts(
            boolean securityRecordPresent,
            boolean securityPlaceholderSuspected,
            boolean securitySourceKnown,
            boolean securityPointInTimeGuaranteed,
            int loadedBarCount,
            int requiredBarsForTechnicalMetrics,
            boolean exactTradeDatePresent,
            LocalDate requestedTradeDate,
            LocalDate effectiveTradeDate,
            Long naturalDayLag,
            boolean tradingCalendarAvailable,
            int missingAmountCount,
            int missingTurnoverRateCount,
            int invalidBarCount,
            List<LocalDate> invalidBarDates,
            Long maximumObservedNaturalDayGap,
            String duplicateProtection,
            boolean sourceConsistencyAssessable,
            List<String> adjustTypesObserved,
            List<String> missingSecurityFields
    ) {
    }
}

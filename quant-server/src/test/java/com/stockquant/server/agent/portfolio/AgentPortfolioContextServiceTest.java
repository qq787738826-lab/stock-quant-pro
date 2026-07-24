package com.stockquant.server.agent.portfolio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.portfolio.AgentPortfolioContextRepository.AccountRecord;
import com.stockquant.server.agent.portfolio.AgentPortfolioContextRepository.EquitySnapshotRecord;
import com.stockquant.server.agent.portfolio.AgentPortfolioContextRepository.PendingOrderRecord;
import com.stockquant.server.agent.portfolio.AgentPortfolioContextRepository.PositionRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentPortfolioContextServiceTest {

    private static final String SYMBOL = "600000";
    private static final LocalDate DATE = LocalDate.of(2026, 7, 24);
    private static final Instant QUERIED_AT = Instant.parse("2026-07-24T04:00:00Z");

    private final ObjectMapper objectMapper =
            new ObjectMapper().findAndRegisterModules();
    private AgentPortfolioContextRepository repository;
    private AgentPortfolioContextService service;

    @BeforeEach
    void setUp() {
        repository = mock(AgentPortfolioContextRepository.class);
        service = new AgentPortfolioContextService(objectMapper, repository);
        when(repository.findAccount(PortfolioContracts.ACCOUNT_ID))
                .thenReturn(Optional.of(account("100000.00", "0.00")));
        when(repository.findRiskSettings()).thenReturn(Map.of(
                "portfolio.max_positions", "5",
                "portfolio.max_position_weight", "0.20"));
        when(repository.findPositions(PortfolioContracts.ACCOUNT_ID, DATE))
                .thenReturn(List.of());
        when(repository.findPendingOrders(PortfolioContracts.ACCOUNT_ID))
                .thenReturn(List.of());
        when(repository.findEquityHistoryBefore(PortfolioContracts.ACCOUNT_ID, DATE))
                .thenReturn(List.of(
                        new EquitySnapshotRecord(DATE.minusDays(1), decimal("100000.00"))));
    }

    @Test
    void createIsRepeatableReadAndReadOnly() throws Exception {
        Transactional annotation = AgentPortfolioContextService.class
                .getMethod("create", String.class, LocalDate.class, Instant.class)
                .getAnnotation(Transactional.class);
        assertTrue(annotation.readOnly());
        assertEquals(Isolation.REPEATABLE_READ, annotation.isolation());
    }

    @Test
    void emptyCurrentAccountProducesCompleteFrozenContext() {
        var context = service.create(SYMBOL, DATE, QUERIED_AT);

        assertTrue(context.path("available").asBoolean());
        assertEquals(PortfolioContracts.CONTEXT_PROFILE,
                context.path("contextProfile").asText());
        assertEquals("100000.00",
                context.path("account").path("recomputedTotalAsset").decimalValue()
                        .setScale(2).toPlainString());
        assertEquals(0, context.path("positions").size());
        assertEquals(0, context.path("pendingOrders").size());
        assertEquals(0, context.path("projectedPositions").size());
        assertTrue(context.path("currentStateOnly").asBoolean());
        assertTrue(context.path("snapshotFrozenForTask").asBoolean());
        assertFalse(context.path("historicalPointInTimeGuaranteed").asBoolean());
        assertTrue(context.path("businessTablesReadOnly").asBoolean());
        assertTrue(context.path("account").path("accountDrawdownAvailable").asBoolean());
        assertTrue(context.path("account").path("dailyLossAvailable").asBoolean());
    }

    @Test
    void historicalAndFutureDatesAreUnavailableWithoutDatabaseReads() {
        for (LocalDate requested : List.of(DATE.minusDays(1), DATE.plusDays(1))) {
            var context = service.create(SYMBOL, requested, QUERIED_AT);
            assertFalse(context.path("available").asBoolean());
            assertEquals(PortfolioContracts.NOT_CURRENT_DATE,
                    context.path("reasonCode").asText());
        }
        verify(repository, never()).findAccount(anyLong());
    }

    @Test
    void recomputesPositionsPendingExposureAndEquityMetrics() {
        when(repository.findAccount(PortfolioContracts.ACCOUNT_ID))
                .thenReturn(Optional.of(account("100000.00", "10005.00")));
        when(repository.findPositions(PortfolioContracts.ACCOUNT_ID, DATE))
                .thenReturn(List.of(position(
                        "600001", 100, "80.0000", "100.0000",
                        DATE.minusDays(2), "110.0000", null, "120.0000")));
        when(repository.findPendingOrders(PortfolioContracts.ACCOUNT_ID))
                .thenReturn(List.of(new PendingOrderRecord(
                        10L, "600002", "BUY", 100, decimal("100.0000"),
                        decimal("10000.00"), decimal("10005.00"), 0, 3L)));
        when(repository.findEquityHistoryBefore(PortfolioContracts.ACCOUNT_ID, DATE))
                .thenReturn(List.of(
                        new EquitySnapshotRecord(DATE.minusDays(1), decimal("120000.00")),
                        new EquitySnapshotRecord(DATE.minusDays(3), decimal("125000.00"))));

        var context = service.create(SYMBOL, DATE, QUERIED_AT);

        assertTrue(context.path("available").asBoolean());
        assertEquals("110000.00",
                context.path("account").path("recomputedTotalAsset")
                        .decimalValue().setScale(2).toPlainString());
        assertEquals("0.12000000",
                context.path("account").path("accountDrawdown")
                        .decimalValue().setScale(8).toPlainString());
        assertEquals("0.08333333",
                context.path("account").path("dailyLossPct")
                        .decimalValue().setScale(8).toPlainString());
        assertEquals(1, context.path("account").path("pendingBuyCount").asInt());
        assertEquals(2, context.path("account").path("projectedPositionCount").asInt());
        assertEquals(List.of("600001", "600002"),
                context.path("projectedPositions").findValuesAsText("symbol"));
        assertEquals("100.0000",
                context.path("positions").get(0).path("markPrice")
                        .decimalValue().setScale(4).toPlainString());
        assertEquals(2, context.path("positions").get(0).path("priceAgeDays").asInt());
    }

    @Test
    void missingOrStaleLocalMarkMakesWholeContextUnavailable() {
        when(repository.findPositions(PortfolioContracts.ACCOUNT_ID, DATE))
                .thenReturn(List.of(position(
                        "600001", 100, "80.0000", null,
                        null, "100.0000", null, null)));
        var missing = service.create(SYMBOL, DATE, QUERIED_AT);
        assertEquals(PortfolioContracts.PRICE_MISSING,
                missing.path("reasonCode").asText());

        when(repository.findPositions(PortfolioContracts.ACCOUNT_ID, DATE))
                .thenReturn(List.of(position(
                        "600001", 100, "80.0000", "100.0000",
                        DATE.minusDays(8), "110.0000", null, null)));
        var stale = service.create(SYMBOL, DATE, QUERIED_AT);
        assertEquals(PortfolioContracts.PRICE_STALE,
                stale.path("reasonCode").asText());
    }

    @Test
    void fourToSevenDayPriceIsUsableButWarns() {
        when(repository.findPositions(PortfolioContracts.ACCOUNT_ID, DATE))
                .thenReturn(List.of(position(
                        "600001", 100, "80.0000", "100.0000",
                        DATE.minusDays(7), "110.0000", null, null)));
        var context = service.create(SYMBOL, DATE, QUERIED_AT);
        assertTrue(context.path("available").asBoolean());
        assertTrue(context.path("completeness").path("priceFreshnessWarning").asBoolean());
    }

    @Test
    void weekendCurrentStateUsesLatestPriorLocalMark() {
        LocalDate weekend = DATE.plusDays(1);
        Instant weekendQueriedAt = Instant.parse("2026-07-25T04:00:00Z");
        when(repository.findPositions(PortfolioContracts.ACCOUNT_ID, weekend))
                .thenReturn(List.of(position(
                        "600001", 100, "80.0000", "100.0000",
                        DATE, "110.0000", null, null)));
        when(repository.findPendingOrders(PortfolioContracts.ACCOUNT_ID))
                .thenReturn(List.of());
        when(repository.findEquityHistoryBefore(PortfolioContracts.ACCOUNT_ID, weekend))
                .thenReturn(List.of(
                        new EquitySnapshotRecord(DATE, decimal("100000.00"))));

        var context = service.create(SYMBOL, weekend, weekendQueriedAt);

        assertTrue(context.path("available").asBoolean());
        assertEquals(DATE.toString(),
                context.path("positions").get(0).path("markTradeDate").asText());
        assertEquals(1, context.path("positions").get(0).path("priceAgeDays").asInt());
    }

    @Test
    void pendingSellIsCrossCheckedWithoutCreatingBuyExposure() {
        when(repository.findPositions(PortfolioContracts.ACCOUNT_ID, DATE))
                .thenReturn(List.of(position(
                        "600001", 200, 100, "80.0000", "100.0000",
                        DATE, "110.0000", null, null)));
        when(repository.findPendingOrders(PortfolioContracts.ACCOUNT_ID))
                .thenReturn(List.of(new PendingOrderRecord(
                        11L, "600001", "SELL", 100, decimal("100.0000"),
                        decimal("10000.00"), decimal("0.00"), 100, null)));

        var context = service.create(SYMBOL, DATE, QUERIED_AT);

        assertTrue(context.path("available").asBoolean());
        assertEquals(1, context.path("account").path("pendingSellCount").asInt());
        assertEquals(0, context.path("account").path("pendingBuyCount").asInt());
        assertEquals("0.00", context.path("account").path("pendingBuyExposure")
                .decimalValue().setScale(2).toPlainString());
        assertEquals(1, context.path("account").path("projectedPositionCount").asInt());

        when(repository.findPendingOrders(PortfolioContracts.ACCOUNT_ID))
                .thenReturn(List.of(new PendingOrderRecord(
                        12L, "600001", "SELL", 200, decimal("100.0000"),
                        decimal("20000.00"), decimal("0.00"), 200, null)));
        assertEquals(PortfolioContracts.ORDER_INVALID,
                service.create(SYMBOL, DATE, QUERIED_AT)
                        .path("reasonCode").asText());
    }

    @Test
    void missingEquityHistoryIsPartialButStillAvailable() {
        when(repository.findEquityHistoryBefore(PortfolioContracts.ACCOUNT_ID, DATE))
                .thenReturn(List.of());
        var context = service.create(SYMBOL, DATE, QUERIED_AT);
        assertTrue(context.path("available").asBoolean());
        assertTrue(context.path("completeness").path("partial").asBoolean());
        assertFalse(context.path("account").path("accountDrawdownAvailable").asBoolean());
        assertTrue(context.path("account").path("accountDrawdown").isNull());
    }

    @Test
    void invalidAccountOrderAndPositionFactsAreRejectedSafely() {
        when(repository.findAccount(PortfolioContracts.ACCOUNT_ID))
                .thenReturn(Optional.of(account("100000.00", "1.00")));
        assertEquals(PortfolioContracts.ACCOUNT_INVALID,
                service.create(SYMBOL, DATE, QUERIED_AT)
                        .path("reasonCode").asText());

        when(repository.findAccount(PortfolioContracts.ACCOUNT_ID))
                .thenReturn(Optional.of(account("100000.00", "0.00")));
        when(repository.findPositions(PortfolioContracts.ACCOUNT_ID, DATE))
                .thenReturn(List.of(position(
                        "600001", 100, "80.0000", "100.0000",
                        DATE, "90.0000", null, null)));
        assertEquals(PortfolioContracts.POSITION_INVALID,
                service.create(SYMBOL, DATE, QUERIED_AT)
                        .path("reasonCode").asText());
    }

    @Test
    void databaseFailurePropagatesInsteadOfBecomingBusinessUnavailable() {
        when(repository.findAccount(PortfolioContracts.ACCOUNT_ID))
                .thenThrow(new DataAccessResourceFailureException("database down"));
        assertThrows(DataAccessResourceFailureException.class,
                () -> service.create(SYMBOL, DATE, QUERIED_AT));
    }

    private static AccountRecord account(String cash, String frozenCash) {
        return new AccountRecord(
                1L, "默认模拟账户", decimal("100000.00"), decimal(cash),
                decimal(frozenCash), decimal("0.00"), decimal("0.00"));
    }

    private static PositionRecord position(
            String symbol,
            int quantity,
            String average,
            String mark,
            LocalDate markDate,
            String highest,
            String stopLoss,
            String target
    ) {
        return position(
                symbol, quantity, quantity, average, mark, markDate,
                highest, stopLoss, target);
    }

    private static PositionRecord position(
            String symbol,
            int quantity,
            int availableQuantity,
            String average,
            String mark,
            LocalDate markDate,
            String highest,
            String stopLoss,
            String target
    ) {
        BigDecimal databaseLast = decimal(mark == null ? "100.0000" : mark);
        BigDecimal averageValue = decimal(average);
        return new PositionRecord(
                symbol,
                quantity,
                availableQuantity,
                averageValue,
                databaseLast,
                databaseLast.multiply(BigDecimal.valueOf(quantity)),
                databaseLast.subtract(averageValue).multiply(BigDecimal.valueOf(quantity)),
                stopLoss == null ? null : decimal(stopLoss),
                target == null ? null : decimal(target),
                decimal("0.0400"),
                decimal(highest),
                null,
                DATE.minusDays(10),
                mark == null ? null : decimal(mark),
                markDate);
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}

package com.stockquant.server.agent.portfolio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.portfolio.AgentPortfolioContextRepository.AccountRecord;
import com.stockquant.server.agent.portfolio.AgentPortfolioContextRepository.EquitySnapshotRecord;
import com.stockquant.server.agent.portfolio.AgentPortfolioContextRepository.PendingOrderRecord;
import com.stockquant.server.agent.portfolio.AgentPortfolioContextRepository.PositionRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@Service
public class AgentPortfolioContextService {

    private static final BigDecimal ZERO_MONEY = new BigDecimal("0.00");
    private static final BigDecimal ZERO_RATIO = new BigDecimal("0.00000000");

    private final ObjectMapper objectMapper;
    private final AgentPortfolioContextRepository repository;

    public AgentPortfolioContextService(
            ObjectMapper objectMapper,
            AgentPortfolioContextRepository repository
    ) {
        this.objectMapper = objectMapper;
        this.repository = repository;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ObjectNode create(
            String requestSymbol,
            LocalDate requestTradeDate,
            Instant queriedAt
    ) {
        LocalDate analysisDate = queriedAt.atZone(PortfolioContracts.MARKET_ZONE).toLocalDate();
        ObjectNode base = baseContext(requestSymbol, requestTradeDate, analysisDate, queriedAt);
        if (!analysisDate.equals(requestTradeDate)) {
            return unavailable(
                    base,
                    PortfolioContracts.NOT_CURRENT_DATE,
                    "2H只支持冻结时刻的当前模拟账户状态，requestTradeDate必须等于analysisDate");
        }

        try {
            AccountRecord account = repository.findAccount(PortfolioContracts.ACCOUNT_ID)
                    .orElseThrow(() -> unavailableError(
                            PortfolioContracts.ACCOUNT_INVALID,
                            "默认模拟账户不存在"));
            Map<String, String> settings = repository.findRiskSettings();
            List<PositionRecord> positions = repository.findPositions(
                    PortfolioContracts.ACCOUNT_ID, requestTradeDate);
            List<PendingOrderRecord> orders = repository.findPendingOrders(
                    PortfolioContracts.ACCOUNT_ID);
            List<EquitySnapshotRecord> equityHistory = repository.findEquityHistoryBefore(
                    PortfolioContracts.ACCOUNT_ID, requestTradeDate);
            return available(base, account, settings, positions, orders, equityHistory, requestTradeDate);
        } catch (UnavailableContext error) {
            return unavailable(base, error.reasonCode, error.getMessage());
        }
    }

    private ObjectNode available(
            ObjectNode context,
            AccountRecord account,
            Map<String, String> settings,
            List<PositionRecord> rawPositions,
            List<PendingOrderRecord> rawOrders,
            List<EquitySnapshotRecord> rawEquityHistory,
            LocalDate requestTradeDate
    ) {
        validateAccount(account);
        int maxPositions = settingInt(settings, "portfolio.max_positions");
        BigDecimal maxPositionWeight = settingDecimal(
                settings, "portfolio.max_position_weight");
        if (maxPositions < 1 || maxPositionWeight.signum() <= 0
                || maxPositionWeight.compareTo(BigDecimal.ONE) > 0) {
            throw unavailableError(
                    PortfolioContracts.SETTINGS_INVALID,
                    "组合持仓数量或仓位权重设置非法");
        }

        List<PendingOrderRecord> orders = rawOrders.stream()
                .sorted(Comparator.comparingLong(PendingOrderRecord::orderId))
                .toList();
        validateOrders(orders);
        BigDecimal pendingBuyFrozen = money(orders.stream()
                .filter(order -> "BUY".equals(order.side()))
                .map(PendingOrderRecord::frozenAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        if (pendingBuyFrozen.compareTo(money(account.frozenCash())) != 0) {
            throw unavailableError(
                    PortfolioContracts.ACCOUNT_INVALID,
                    "账户frozenCash与待确认BUY委托冻结金额汇总不一致");
        }

        Map<String, BigDecimal> pendingBuyBySymbol = new LinkedHashMap<>();
        Map<String, Integer> pendingSellBySymbol = new LinkedHashMap<>();
        for (PendingOrderRecord order : orders) {
            if ("BUY".equals(order.side())) {
                pendingBuyBySymbol.merge(
                        order.symbol(), money(order.grossAmount()), BigDecimal::add);
            } else {
                pendingSellBySymbol.merge(
                        order.symbol(), order.frozenQuantity(), Integer::sum);
            }
        }
        pendingBuyBySymbol.replaceAll((symbol, value) -> money(value));

        List<PositionFacts> positions = new ArrayList<>();
        Set<String> seenSymbols = new LinkedHashSet<>();
        for (PositionRecord position : rawPositions.stream()
                .sorted(Comparator.comparing(PositionRecord::symbol))
                .toList()) {
            if (!seenSymbols.add(position.symbol())) {
                throw unavailableError(
                        PortfolioContracts.POSITION_INVALID,
                        "持仓symbol重复：" + position.symbol());
            }
            positions.add(positionFacts(position, requestTradeDate));
        }
        Map<String, PositionFacts> frozenPositionsBySymbol = new LinkedHashMap<>();
        positions.forEach(position -> frozenPositionsBySymbol.put(position.symbol(), position));
        for (Map.Entry<String, Integer> pendingSell : pendingSellBySymbol.entrySet()) {
            PositionFacts position = frozenPositionsBySymbol.get(pendingSell.getKey());
            if (position == null
                    || pendingSell.getValue()
                    > position.quantity() - position.availableQuantity()) {
                throw unavailableError(
                        PortfolioContracts.ORDER_INVALID,
                        "pending SELL frozen quantity is inconsistent with position: "
                                + pendingSell.getKey());
            }
        }

        BigDecimal marketValue = money(positions.stream()
                .map(PositionFacts::marketValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal unrealizedPnl = money(positions.stream()
                .map(PositionFacts::unrealizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal cash = money(account.cash());
        BigDecimal frozenCash = money(account.frozenCash());
        BigDecimal availableCash = money(cash.subtract(frozenCash));
        BigDecimal totalAsset = money(cash.add(marketValue));
        if (totalAsset.signum() <= 0) {
            throw unavailableError(
                    PortfolioContracts.ACCOUNT_INVALID,
                    "重算总资产必须大于0");
        }
        BigDecimal availableCashRatio = ratio(availableCash, totalAsset);

        List<PositionFacts> weightedPositions = positions.stream()
                .map(position -> position.withWeight(ratio(position.marketValue(), totalAsset)))
                .toList();

        TreeSet<String> projectedSymbols = new TreeSet<>(seenSymbols);
        projectedSymbols.addAll(pendingBuyBySymbol.keySet());
        Map<String, PositionFacts> positionsBySymbol = new LinkedHashMap<>();
        weightedPositions.forEach(position -> positionsBySymbol.put(position.symbol(), position));
        List<ProjectedPositionFacts> projected = projectedSymbols.stream()
                .map(symbol -> {
                    PositionFacts position = positionsBySymbol.get(symbol);
                    BigDecimal currentValue = position == null
                            ? ZERO_MONEY : position.marketValue();
                    BigDecimal pending = pendingBuyBySymbol.getOrDefault(symbol, ZERO_MONEY);
                    BigDecimal projectedValue = money(currentValue.add(pending));
                    return new ProjectedPositionFacts(
                            symbol,
                            position != null,
                            currentValue,
                            pending,
                            projectedValue,
                            ratio(projectedValue, totalAsset));
                })
                .toList();

        EquityFacts equity = equityFacts(rawEquityHistory, totalAsset);
        boolean stalePriceWarning = weightedPositions.stream()
                .anyMatch(position -> position.priceAgeDays() >= 4);
        long pendingBuyCount = orders.stream().filter(order -> "BUY".equals(order.side())).count();
        long pendingSellCount = orders.size() - pendingBuyCount;

        context.put("available", true);
        ObjectNode accountNode = context.putObject("account");
        accountNode.put("accountId", account.id());
        accountNode.put("name", account.name());
        putDecimal(accountNode, "initialCapital", money(account.initialCapital()));
        putDecimal(accountNode, "cash", cash);
        putDecimal(accountNode, "frozenCash", frozenCash);
        putDecimal(accountNode, "availableCash", availableCash);
        putDecimal(accountNode, "recomputedMarketValue", marketValue);
        putDecimal(accountNode, "recomputedTotalAsset", totalAsset);
        putDecimal(accountNode, "realizedPnl", money(account.realizedPnl()));
        putDecimal(accountNode, "recomputedUnrealizedPnl", unrealizedPnl);
        putDecimal(accountNode, "totalFees", money(account.totalFees()));
        accountNode.put("positionCount", weightedPositions.size());
        accountNode.put("pendingBuyCount", pendingBuyCount);
        accountNode.put("pendingSellCount", pendingSellCount);
        putDecimal(accountNode, "availableCashRatio", availableCashRatio);
        accountNode.put("maxPositions", maxPositions);
        putDecimal(accountNode, "maxPositionWeight", ratioValue(maxPositionWeight));
        accountNode.put("projectedPositionCount", projected.size());
        putDecimal(accountNode, "pendingBuyExposure", money(
                pendingBuyBySymbol.values().stream()
                        .reduce(BigDecimal.ZERO, BigDecimal::add)));
        accountNode.put("accountDrawdownAvailable", equity.accountDrawdownAvailable());
        putNullableDecimal(accountNode, "accountDrawdown", equity.accountDrawdown());
        accountNode.put("dailyLossAvailable", equity.dailyLossAvailable());
        putNullableDecimal(accountNode, "dailyLossPct", equity.dailyLossPct());

        ArrayNode positionNodes = context.putArray("positions");
        weightedPositions.forEach(position -> positionNodes.add(positionNode(position)));
        ArrayNode orderNodes = context.putArray("pendingOrders");
        orders.forEach(order -> orderNodes.add(orderNode(order)));
        ArrayNode projectedNodes = context.putArray("projectedPositions");
        projected.forEach(value -> projectedNodes.add(projectedNode(value)));

        ObjectNode equityNode = context.putObject("equityHistory");
        equityNode.put("accountDrawdownAvailable", equity.accountDrawdownAvailable());
        putNullableDecimal(equityNode, "historicalPeakTotalAsset", equity.historicalPeakTotalAsset());
        putNullableDecimal(equityNode, "accountDrawdown", equity.accountDrawdown());
        equityNode.put("dailyLossAvailable", equity.dailyLossAvailable());
        putNullable(equityNode, "previousSnapshotDate", equity.previousSnapshotDate());
        putNullableDecimal(equityNode, "previousTotalAsset", equity.previousTotalAsset());
        putNullableDecimal(equityNode, "dailyLossPct", equity.dailyLossPct());
        equityNode.put("snapshotCount", equity.snapshotCount());

        ObjectNode completeness = context.putObject("completeness");
        completeness.put("accountDrawdownAvailable", equity.accountDrawdownAvailable());
        completeness.put("dailyLossAvailable", equity.dailyLossAvailable());
        completeness.put("priceFreshnessWarning", stalePriceWarning);
        completeness.put("partial",
                !equity.accountDrawdownAvailable() || !equity.dailyLossAvailable());
        ArrayNode limitations = context.putArray("limitations");
        limitations.add("CURRENT_SIMULATED_ACCOUNT_STATE_ONLY");
        limitations.add("NO_HISTORICAL_PORTFOLIO_POINT_IN_TIME_GUARANTEE");
        limitations.add("LOCAL_QFQ_MARK_PRICE_ON_OR_BEFORE_REQUEST_DATE");
        limitations.add("RESEARCH_AND_SIMULATION_ONLY");
        return context;
    }

    private PositionFacts positionFacts(PositionRecord value, LocalDate requestTradeDate) {
        if (value.symbol() == null || !value.symbol().matches("^[0-9]{6}$")
                || value.quantity() <= 0
                || value.availableQuantity() < 0
                || value.availableQuantity() > value.quantity()
                || !positive(value.averageCost())
                || !positive(value.databaseLastPrice())
                || !positive(value.highestPrice())
                || value.highestPrice().compareTo(value.databaseLastPrice()) < 0
                || value.trailingStopPct() == null
                || value.trailingStopPct().signum() < 0
                || value.trailingStopPct().compareTo(BigDecimal.ONE) >= 0
                || value.stopLoss() != null && !positive(value.stopLoss())
                || value.targetPrice() != null && !positive(value.targetPrice())) {
            throw unavailableError(
                    PortfolioContracts.POSITION_INVALID,
                    "持仓字段非法：" + value.symbol());
        }
        BigDecimal databaseMarketValue = money(
                value.databaseLastPrice().multiply(BigDecimal.valueOf(value.quantity())));
        BigDecimal databaseUnrealized = money(
                value.databaseLastPrice().subtract(value.averageCost())
                        .multiply(BigDecimal.valueOf(value.quantity())));
        if (value.databaseMarketValue() == null || value.databaseUnrealizedPnl() == null
                || databaseMarketValue.compareTo(money(value.databaseMarketValue())) != 0
                || databaseUnrealized.compareTo(money(value.databaseUnrealizedPnl())) != 0) {
            throw unavailableError(
                    PortfolioContracts.POSITION_INVALID,
                    "持仓数据库生成汇总与明细不一致：" + value.symbol());
        }
        if (value.markPrice() == null || value.markTradeDate() == null) {
            throw unavailableError(
                    PortfolioContracts.PRICE_MISSING,
                    "持仓缺少请求日期及以前的本地QFQ估值价：" + value.symbol());
        }
        if (!positive(value.markPrice())
                || value.markTradeDate().isAfter(requestTradeDate)
                || value.highestPrice().compareTo(value.markPrice()) < 0) {
            throw unavailableError(
                    PortfolioContracts.POSITION_INVALID,
                    "持仓估值价或估值日期非法：" + value.symbol());
        }
        long age = ChronoUnit.DAYS.between(value.markTradeDate(), requestTradeDate);
        if (age > PortfolioContracts.MAX_PRICE_AGE_DAYS) {
            throw unavailableError(
                    PortfolioContracts.PRICE_STALE,
                    "持仓估值价超过7个自然日：" + value.symbol());
        }
        BigDecimal marketValue = money(
                value.markPrice().multiply(BigDecimal.valueOf(value.quantity())));
        BigDecimal unrealizedPnl = money(
                value.markPrice().subtract(value.averageCost())
                        .multiply(BigDecimal.valueOf(value.quantity())));
        BigDecimal trailingStopPrice = price(
                value.highestPrice().multiply(BigDecimal.ONE.subtract(value.trailingStopPct())));
        return new PositionFacts(
                value.symbol(),
                value.quantity(),
                value.availableQuantity(),
                price(value.averageCost()),
                price(value.databaseLastPrice()),
                price(value.markPrice()),
                value.markTradeDate(),
                age,
                marketValue,
                unrealizedPnl,
                ZERO_RATIO,
                nullablePrice(value.stopLoss()),
                nullablePrice(value.targetPrice()),
                ratioValue(value.trailingStopPct()),
                price(value.highestPrice()),
                trailingStopPrice,
                value.sourcePlanId(),
                value.lastBuyDate());
    }

    private void validateAccount(AccountRecord value) {
        if (value.id() != PortfolioContracts.ACCOUNT_ID
                || value.name() == null || value.name().isBlank()
                || !positive(value.initialCapital())
                || value.cash() == null || value.cash().signum() < 0
                || value.frozenCash() == null || value.frozenCash().signum() < 0
                || value.frozenCash().compareTo(value.cash()) > 0
                || value.realizedPnl() == null
                || value.totalFees() == null || value.totalFees().signum() < 0) {
            throw unavailableError(
                    PortfolioContracts.ACCOUNT_INVALID,
                    "默认模拟账户资金字段非法");
        }
    }

    private void validateOrders(List<PendingOrderRecord> orders) {
        long previousId = 0;
        for (PendingOrderRecord order : orders) {
            BigDecimal expectedGross = order.limitPrice() == null
                    ? null
                    : money(order.limitPrice().multiply(BigDecimal.valueOf(order.quantity())));
            boolean commonInvalid = order.orderId() <= previousId
                    || order.symbol() == null || !order.symbol().matches("^[0-9]{6}$")
                    || !Set.of("BUY", "SELL").contains(order.side())
                    || order.quantity() <= 0
                    || !positive(order.limitPrice())
                    || order.grossAmount() == null
                    || expectedGross.compareTo(money(order.grossAmount())) != 0
                    || order.frozenAmount() == null || order.frozenAmount().signum() < 0
                    || order.frozenQuantity() < 0
                    || order.frozenQuantity() > order.quantity();
            boolean sideInvalid = "BUY".equals(order.side())
                    ? money(order.frozenAmount()).compareTo(money(order.grossAmount())) < 0
                    || order.frozenQuantity() != 0
                    : money(order.frozenAmount()).signum() != 0
                    || order.frozenQuantity() <= 0;
            if (commonInvalid || sideInvalid) {
                throw unavailableError(
                        PortfolioContracts.ORDER_INVALID,
                        "待确认委托字段非法：orderId=" + order.orderId());
            }
            previousId = order.orderId();
        }
    }

    private EquityFacts equityFacts(
            List<EquitySnapshotRecord> raw,
            BigDecimal currentTotalAsset
    ) {
        List<EquitySnapshotRecord> values = raw.stream()
                .sorted(Comparator.comparing(EquitySnapshotRecord::snapshotDate).reversed())
                .toList();
        LocalDate previousDate = null;
        BigDecimal previous = null;
        BigDecimal peak = null;
        for (EquitySnapshotRecord value : values) {
            if (value.snapshotDate() == null || !positive(value.totalAsset())) {
                throw unavailableError(
                        PortfolioContracts.ACCOUNT_INVALID,
                        "历史权益快照字段非法");
            }
            if (previousDate == null) {
                previousDate = value.snapshotDate();
                previous = money(value.totalAsset());
            }
            BigDecimal candidate = money(value.totalAsset());
            if (peak == null || candidate.compareTo(peak) > 0) {
                peak = candidate;
            }
        }
        BigDecimal drawdown = peak == null
                ? null
                : positiveLossRatio(peak, currentTotalAsset);
        BigDecimal dailyLoss = previous == null
                ? null
                : positiveLossRatio(previous, currentTotalAsset);
        return new EquityFacts(
                peak != null,
                peak,
                drawdown,
                previous != null,
                previousDate,
                previous,
                dailyLoss,
                values.size());
    }

    private ObjectNode baseContext(
            String requestSymbol,
            LocalDate requestTradeDate,
            LocalDate analysisDate,
            Instant queriedAt
    ) {
        ObjectNode context = objectMapper.createObjectNode();
        context.put("available", false);
        context.put("queriedAt", queriedAt.toString());
        ObjectNode scope = context.putObject("queryScope");
        scope.put("symbol", requestSymbol);
        scope.put("tradeDate", requestTradeDate.toString());
        context.put("producer", PortfolioContracts.PRODUCER);
        context.put("producerVersion", PortfolioContracts.PRODUCER_VERSION);
        context.put("contextProfile", PortfolioContracts.CONTEXT_PROFILE);
        context.put("schemaVersion", PortfolioContracts.CONTEXT_SCHEMA_VERSION);
        context.put("accountId", PortfolioContracts.ACCOUNT_ID);
        context.put("requestTradeDate", requestTradeDate.toString());
        context.put("analysisDate", analysisDate.toString());
        context.put("marketTimezone", PortfolioContracts.MARKET_TIMEZONE);
        context.put("currentStateOnly", true);
        context.put("snapshotFrozenForTask", true);
        context.put("historicalPointInTimeGuaranteed", false);
        context.put("businessTablesReadOnly", true);
        context.put("sourceType", "DATABASE");
        ArrayNode sourceTables = context.putArray("sourceTables");
        List.of(
                "portfolio_accounts",
                "positions",
                "manual_orders",
                "daily_bars",
                "account_equity_snapshots",
                "app_settings"
        ).forEach(sourceTables::add);
        return context;
    }

    private ObjectNode unavailable(ObjectNode context, String reasonCode, String reason) {
        context.put("available", false);
        context.put("reasonCode", reasonCode);
        context.put("reason", reason);
        return context;
    }

    private ObjectNode positionNode(PositionFacts value) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("symbol", value.symbol());
        node.put("quantity", value.quantity());
        node.put("availableQuantity", value.availableQuantity());
        putDecimal(node, "averageCost", value.averageCost());
        putDecimal(node, "databaseLastPrice", value.databaseLastPrice());
        putDecimal(node, "markPrice", value.markPrice());
        node.put("markTradeDate", value.markTradeDate().toString());
        node.put("priceAgeDays", value.priceAgeDays());
        putDecimal(node, "marketValue", value.marketValue());
        putDecimal(node, "unrealizedPnl", value.unrealizedPnl());
        putDecimal(node, "positionWeight", value.positionWeight());
        putNullableDecimal(node, "stopLoss", value.stopLoss());
        putNullableDecimal(node, "targetPrice", value.targetPrice());
        putDecimal(node, "trailingStopPct", value.trailingStopPct());
        putDecimal(node, "highestPrice", value.highestPrice());
        putDecimal(node, "trailingStopPrice", value.trailingStopPrice());
        putNullable(node, "sourcePlanId", value.sourcePlanId());
        putNullable(node, "lastBuyDate", value.lastBuyDate());
        return node;
    }

    private ObjectNode orderNode(PendingOrderRecord value) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("orderId", value.orderId());
        node.put("symbol", value.symbol());
        node.put("side", value.side());
        node.put("quantity", value.quantity());
        putDecimal(node, "limitPrice", price(value.limitPrice()));
        putDecimal(node, "grossAmount", money(value.grossAmount()));
        putDecimal(node, "frozenAmount", money(value.frozenAmount()));
        node.put("frozenQuantity", value.frozenQuantity());
        putNullable(node, "tradePlanId", value.tradePlanId());
        return node;
    }

    private ObjectNode projectedNode(ProjectedPositionFacts value) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("symbol", value.symbol());
        node.put("currentPositionPresent", value.currentPositionPresent());
        putDecimal(node, "currentPositionValue", value.currentPositionValue());
        putDecimal(node, "pendingBuyExposure", value.pendingBuyExposure());
        putDecimal(node, "projectedPositionValue", value.projectedPositionValue());
        putDecimal(node, "projectedPositionWeight", value.projectedPositionWeight());
        return node;
    }

    private static int settingInt(Map<String, String> settings, String key) {
        String value = settings.get(key);
        if (value == null || value.isBlank()) {
            throw unavailableError(
                    PortfolioContracts.SETTINGS_INVALID,
                    "缺少组合设置：" + key);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw unavailableError(
                    PortfolioContracts.SETTINGS_INVALID,
                    "组合设置不是整数：" + key);
        }
    }

    private static BigDecimal settingDecimal(Map<String, String> settings, String key) {
        String value = settings.get(key);
        if (value == null || value.isBlank()) {
            throw unavailableError(
                    PortfolioContracts.SETTINGS_INVALID,
                    "缺少组合设置：" + key);
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException error) {
            throw unavailableError(
                    PortfolioContracts.SETTINGS_INVALID,
                    "组合设置不是十进制数：" + key);
        }
    }

    private static BigDecimal positiveLossRatio(BigDecimal reference, BigDecimal current) {
        if (current.compareTo(reference) >= 0) {
            return ZERO_RATIO;
        }
        return ratio(reference.subtract(current), reference);
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal price(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal nullablePrice(BigDecimal value) {
        return value == null ? null : price(value);
    }

    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (denominator.signum() <= 0) {
            throw unavailableError(
                    PortfolioContracts.ACCOUNT_INVALID,
                    "比例分母必须大于0");
        }
        return ratioValue(numerator.divide(denominator, 8, RoundingMode.HALF_UP));
    }

    private static BigDecimal ratioValue(BigDecimal value) {
        return value.setScale(8, RoundingMode.HALF_UP);
    }

    private static void putDecimal(ObjectNode node, String field, BigDecimal value) {
        node.put(field, value);
    }

    private static void putNullableDecimal(ObjectNode node, String field, BigDecimal value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static void putNullable(ObjectNode node, String field, Object value) {
        if (value == null) {
            node.putNull(field);
        } else if (value instanceof Long number) {
            node.put(field, number);
        } else {
            node.put(field, value.toString());
        }
    }

    private static UnavailableContext unavailableError(String reasonCode, String message) {
        return new UnavailableContext(reasonCode, message);
    }

    private record PositionFacts(
            String symbol,
            int quantity,
            int availableQuantity,
            BigDecimal averageCost,
            BigDecimal databaseLastPrice,
            BigDecimal markPrice,
            LocalDate markTradeDate,
            long priceAgeDays,
            BigDecimal marketValue,
            BigDecimal unrealizedPnl,
            BigDecimal positionWeight,
            BigDecimal stopLoss,
            BigDecimal targetPrice,
            BigDecimal trailingStopPct,
            BigDecimal highestPrice,
            BigDecimal trailingStopPrice,
            Long sourcePlanId,
            LocalDate lastBuyDate
    ) {
        PositionFacts withWeight(BigDecimal value) {
            return new PositionFacts(
                    symbol,
                    quantity,
                    availableQuantity,
                    averageCost,
                    databaseLastPrice,
                    markPrice,
                    markTradeDate,
                    priceAgeDays,
                    marketValue,
                    unrealizedPnl,
                    value,
                    stopLoss,
                    targetPrice,
                    trailingStopPct,
                    highestPrice,
                    trailingStopPrice,
                    sourcePlanId,
                    lastBuyDate);
        }
    }

    private record ProjectedPositionFacts(
            String symbol,
            boolean currentPositionPresent,
            BigDecimal currentPositionValue,
            BigDecimal pendingBuyExposure,
            BigDecimal projectedPositionValue,
            BigDecimal projectedPositionWeight
    ) {
    }

    private record EquityFacts(
            boolean accountDrawdownAvailable,
            BigDecimal historicalPeakTotalAsset,
            BigDecimal accountDrawdown,
            boolean dailyLossAvailable,
            LocalDate previousSnapshotDate,
            BigDecimal previousTotalAsset,
            BigDecimal dailyLossPct,
            int snapshotCount
    ) {
    }

    private static final class UnavailableContext extends RuntimeException {
        private final String reasonCode;

        private UnavailableContext(String reasonCode, String message) {
            super(message);
            this.reasonCode = reasonCode;
        }
    }
}

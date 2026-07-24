package com.stockquant.server.agent.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockquant.server.agent.exception.AgentResponseValidationException;
import com.stockquant.server.agent.model.AgentModels.AgentOutput;
import com.stockquant.server.agent.model.AgentModels.AgentTeamRequest;
import com.stockquant.server.agent.model.AgentModels.AgentTeamResponse;
import com.stockquant.server.agent.model.AgentModels.Evidence;
import com.stockquant.server.agent.model.AgentModels.FormalVeto;
import com.stockquant.server.agent.model.AgentTypes.AgentCode;
import com.stockquant.server.agent.model.AgentTypes.EvidenceCategory;
import com.stockquant.server.agent.model.AgentTypes.EvidenceSourceType;
import com.stockquant.server.agent.model.AgentTypes.GateStatus;
import com.stockquant.server.agent.model.AgentTypes.RunDecision;
import com.stockquant.server.agent.model.AgentTypes.RunStatus;
import com.stockquant.server.agent.portfolio.PortfolioContracts;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

final class AgentStage2HPositionRiskValidator {

    private static final Set<String> BASE_FIELDS = Set.of(
            "available", "queriedAt", "queryScope", "producer", "producerVersion",
            "contextProfile", "schemaVersion", "accountId", "requestTradeDate",
            "analysisDate", "marketTimezone", "currentStateOnly", "snapshotFrozenForTask",
            "historicalPointInTimeGuaranteed", "businessTablesReadOnly", "sourceType",
            "sourceTables"
    );
    private static final Set<String> ACCOUNT_FIELDS = Set.of(
            "accountId", "name", "initialCapital", "cash", "frozenCash", "availableCash",
            "recomputedMarketValue", "recomputedTotalAsset", "realizedPnl",
            "recomputedUnrealizedPnl", "totalFees", "positionCount", "pendingBuyCount",
            "pendingSellCount", "availableCashRatio", "maxPositions", "maxPositionWeight",
            "projectedPositionCount", "pendingBuyExposure", "accountDrawdownAvailable",
            "accountDrawdown", "dailyLossAvailable", "dailyLossPct"
    );
    private static final Set<String> POSITION_FIELDS = Set.of(
            "symbol", "quantity", "availableQuantity", "averageCost", "databaseLastPrice",
            "markPrice", "markTradeDate", "priceAgeDays", "marketValue", "unrealizedPnl",
            "positionWeight", "stopLoss", "targetPrice", "trailingStopPct", "highestPrice",
            "trailingStopPrice", "sourcePlanId", "lastBuyDate"
    );
    private static final Set<String> ORDER_FIELDS = Set.of(
            "orderId", "symbol", "side", "quantity", "limitPrice", "grossAmount",
            "frozenAmount", "frozenQuantity", "tradePlanId"
    );
    private static final Set<String> PROJECTED_FIELDS = Set.of(
            "symbol", "currentPositionPresent", "currentPositionValue", "pendingBuyExposure",
            "projectedPositionValue", "projectedPositionWeight"
    );
    private static final Set<String> EQUITY_FIELDS = Set.of(
            "accountDrawdownAvailable", "historicalPeakTotalAsset", "accountDrawdown",
            "dailyLossAvailable", "previousSnapshotDate", "previousTotalAsset",
            "dailyLossPct", "snapshotCount"
    );
    private static final Set<String> COMPLETENESS_FIELDS = Set.of(
            "accountDrawdownAvailable", "dailyLossAvailable",
            "priceFreshnessWarning", "partial"
    );
    private static final List<String> SOURCE_TABLES = List.of(
            "portfolio_accounts", "positions", "manual_orders", "daily_bars",
            "account_equity_snapshots", "app_settings"
    );
    private static final List<String> LIMITATIONS = List.of(
            "CURRENT_SIMULATED_ACCOUNT_STATE_ONLY",
            "NO_HISTORICAL_PORTFOLIO_POINT_IN_TIME_GUARANTEE",
            "LOCAL_QFQ_MARK_PRICE_ON_OR_BEFORE_REQUEST_DATE",
            "RESEARCH_AND_SIMULATION_ONLY"
    );
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(8);

    private AgentStage2HPositionRiskValidator() {
    }

    static void validate(
            AgentTeamRequest request,
            AgentTeamResponse response,
            AgentOutput run,
            AgentOutput dataQuality
    ) {
        require(run.agentCode() == AgentCode.POSITION_RISK,
                "阶段2H校验目标必须是POSITION_RISK");
        ContextFacts facts;
        try {
            facts = validateContext(request);
        } catch (ContractException invalid) {
            validateUnavailableRun(run, PortfolioContracts.INPUT_INVALID);
            require(response.vetoes().isEmpty(),
                    "非法portfolioContext不得产生正式veto");
            return;
        }
        if (!facts.available()) {
            validateUnavailableRun(run, facts.reasonCode());
            require(response.vetoes().isEmpty(),
                    "不可用portfolioContext不得产生正式veto");
            return;
        }

        require(run.status() == (facts.partial() ? RunStatus.PARTIAL : RunStatus.COMPLETED),
                "阶段2H POSITION_RISK status与权益完整性不一致");
        require(run.errors().isEmpty(), "有效portfolioContext不得返回POSITION_RISK错误");
        require(run.findings().size() == 5, "阶段2H必须固定输出五类finding");
        require(run.evidence().size() == 1, "阶段2H必须输出唯一portfolio evidence");
        require(run.confidence() == facts.confidence(),
                "阶段2H POSITION_RISK confidence重算不一致");

        Evidence evidence = run.evidence().get(0);
        String evidenceId = "position-risk-portfolio-" + request.contextHash();
        require(evidenceId.equals(evidence.evidenceId())
                        && evidence.category() == EvidenceCategory.PORTFOLIO_STATE
                        && evidence.sourceType() == EvidenceSourceType.JAVA_ENGINE
                        && PortfolioContracts.PRODUCER.equals(evidence.sourceName())
                        && "contextSnapshot.portfolioContext".equals(evidence.sourceRef())
                        && Objects.equals(evidence.symbol(), request.symbol())
                        && Objects.equals(evidence.tradeDate(), request.tradeDate())
                        && Objects.equals(evidence.contentHash(), request.contextHash())
                        && evidence.fields().isObject()
                        && fields(evidence.fields()).equals(Set.of("portfolioContext"))
                        && jsonSemanticallyEqual(
                        evidence.fields().get("portfolioContext"),
                        request.contextSnapshot().get("portfolioContext")),
                "阶段2H portfolio evidence契约无效");

        for (int index = 0; index < PortfolioContracts.FINDING_CODES.size(); index++) {
            var finding = run.findings().get(index);
            String code = PortfolioContracts.FINDING_CODES.get(index);
            String expectedId = "position-risk-finding-%02d-%s-%s".formatted(
                    index + 1,
                    code.toLowerCase().replace('_', '-'),
                    request.contextHash());
            require(code.equals(finding.code())
                            && expectedId.equals(finding.findingId())
                            && finding.evidenceIds().equals(List.of(evidenceId))
                            && notBlank(finding.title())
                            && notBlank(finding.detail())
                            && !containsExecutionInstruction(finding.detail()),
                    "阶段2H finding代码、顺序、ID或证据引用无效");
        }

        List<VetoSpec> expectedVetoes = facts.vetoes();
        require(response.vetoes().size() == expectedVetoes.size(),
                "阶段2H正式veto数量不一致");
        for (int index = 0; index < expectedVetoes.size(); index++) {
            VetoSpec expected = expectedVetoes.get(index);
            FormalVeto actual = response.vetoes().get(index);
            String expectedId = "position-risk-veto-%02d-%s-%s".formatted(
                    index + 1,
                    expected.code().toLowerCase().replace('_', '-'),
                    request.contextHash());
            require(expectedId.equals(actual.vetoId())
                            && expected.code().equals(actual.vetoCode())
                            && expected.reason().equals(actual.reason())
                            && actual.agentCode() == AgentCode.POSITION_RISK
                            && actual.taskId() == request.taskId()
                            && actual.runId() == request.runIds().positionRisk()
                            && actual.evidenceIds().equals(List.of(evidenceId))
                            && !containsExecutionInstruction(actual.reason()),
                    "阶段2H正式veto顺序、ID、code、reason或证据引用无效");
        }

        boolean veto = !expectedVetoes.isEmpty();
        require(run.veto() == veto, "阶段2H POSITION_RISK veto标志不一致");
        if (veto) {
            require(run.gateStatus() == GateStatus.BLOCKED
                            && run.decision() == RunDecision.REJECT
                            && run.score() == 0,
                    "阶段2H正式veto运行状态必须为BLOCKED/REJECT/0");
        } else {
            boolean warning = facts.warning();
            require(run.gateStatus() == (warning ? GateStatus.WARN : GateStatus.PASS)
                            && run.decision() == (warning ? RunDecision.WARN : RunDecision.PASS)
                            && run.score() == facts.score(),
                    "阶段2H无veto运行状态或score不一致");
        }
        require(!containsExecutionInstruction(run.summary()),
                "阶段2H POSITION_RISK摘要不得包含交易执行指令");
        if (dataQuality.gateStatus() == GateStatus.BLOCKED) {
            require(run.status() == RunStatus.COMPLETED || run.status() == RunStatus.PARTIAL,
                    "DATA_QUALITY阻断时有效POSITION_RISK仍必须执行");
        }
    }

    private static ContextFacts validateContext(AgentTeamRequest request) {
        JsonNode context = request.contextSnapshot().get("portfolioContext");
        requireContract(context != null && context.isObject(), "portfolioContext必须是对象");
        boolean available = requiredBoolean(context, "available");
        Set<String> expected = new HashSet<>(BASE_FIELDS);
        if (available) {
            expected.addAll(Set.of(
                    "account", "positions", "pendingOrders", "projectedPositions",
                    "equityHistory", "completeness", "limitations"));
        } else {
            expected.addAll(Set.of("reasonCode", "reason"));
        }
        requireContract(fields(context).equals(expected), "portfolioContext字段白名单无效");
        requireContract(
                PortfolioContracts.PRODUCER.equals(text(context, "producer"))
                        && PortfolioContracts.PRODUCER_VERSION.equals(
                        text(context, "producerVersion"))
                        && PortfolioContracts.CONTEXT_PROFILE.equals(
                        text(context, "contextProfile"))
                        && PortfolioContracts.CONTEXT_SCHEMA_VERSION.equals(
                        text(context, "schemaVersion"))
                        && number(context, "accountId").longValueExact()
                        == PortfolioContracts.ACCOUNT_ID
                        && request.tradeDate().toString().equals(
                        text(context, "requestTradeDate"))
                        && request.tradeDate().toString().equals(
                        text(context, "analysisDate"))
                        && PortfolioContracts.MARKET_TIMEZONE.equals(
                        text(context, "marketTimezone"))
                        && requiredBoolean(context, "currentStateOnly")
                        && requiredBoolean(context, "snapshotFrozenForTask")
                        && !requiredBoolean(context, "historicalPointInTimeGuaranteed")
                        && requiredBoolean(context, "businessTablesReadOnly")
                        && "DATABASE".equals(text(context, "sourceType")),
                "portfolioContext身份或安全边界无效");
        JsonNode scope = object(context, "queryScope", Set.of("symbol", "tradeDate"));
        requireContract(request.symbol().equals(text(scope, "symbol"))
                        && request.tradeDate().toString().equals(text(scope, "tradeDate")),
                "portfolioContext queryScope无效");
        requireContract(strings(context.get("sourceTables")).equals(SOURCE_TABLES),
                "portfolioContext sourceTables无效");
        Instant queriedAt = Instant.parse(text(context, "queriedAt"));
        requireContract(queriedAt.atZone(PortfolioContracts.MARKET_ZONE).toLocalDate()
                        .equals(request.tradeDate()),
                "portfolioContext queriedAt不是请求日当前状态");
        if (!available) {
            String reasonCode = text(context, "reasonCode");
            requireContract(PortfolioContracts.UNAVAILABLE_REASON_CODES.contains(reasonCode)
                            && notBlank(text(context, "reason")),
                    "不可用portfolioContext reason无效");
            return ContextFacts.unavailable(reasonCode);
        }
        requireContract(strings(context.get("limitations")).equals(LIMITATIONS),
                "portfolioContext limitations无效");

        JsonNode account = object(context, "account", ACCOUNT_FIELDS);
        JsonNode positions = array(context, "positions");
        JsonNode orders = array(context, "pendingOrders");
        JsonNode projected = array(context, "projectedPositions");
        JsonNode equity = object(context, "equityHistory", EQUITY_FIELDS);
        JsonNode completeness = object(context, "completeness", COMPLETENESS_FIELDS);

        BigDecimal cash = money(number(account, "cash"));
        BigDecimal frozenCash = money(number(account, "frozenCash"));
        BigDecimal availableCash = money(number(account, "availableCash"));
        BigDecimal marketValue = money(number(account, "recomputedMarketValue"));
        BigDecimal totalAsset = money(number(account, "recomputedTotalAsset"));
        requireContract(number(account, "accountId").longValueExact()
                        == PortfolioContracts.ACCOUNT_ID
                        && notBlank(text(account, "name"))
                        && number(account, "initialCapital").signum() > 0
                        && cash.signum() >= 0
                        && frozenCash.signum() >= 0
                        && frozenCash.compareTo(cash) <= 0
                        && availableCash.equals(money(cash.subtract(frozenCash)))
                        && totalAsset.signum() > 0
                        && totalAsset.equals(money(cash.add(marketValue)))
                        && number(account, "realizedPnl") != null
                        && number(account, "totalFees").signum() >= 0,
                "账户资金重算无效");
        int maxPositions = integer(account, "maxPositions", 1);
        BigDecimal maxWeight = number(account, "maxPositionWeight");
        requireContract(maxWeight.signum() > 0 && maxWeight.compareTo(BigDecimal.ONE) <= 0,
                "组合限制无效");

        List<JsonNode> positionValues = nodes(positions);
        List<String> positionSymbols = new ArrayList<>();
        Map<String, BigDecimal> positionMarketValues = new HashMap<>();
        BigDecimal marketSum = BigDecimal.ZERO;
        BigDecimal pnlSum = BigDecimal.ZERO;
        boolean stale = false;
        for (JsonNode position : positionValues) {
            requireContract(fields(position).equals(POSITION_FIELDS), "position字段白名单无效");
            String symbol = symbol(position, "symbol");
            positionSymbols.add(symbol);
            int quantity = integer(position, "quantity", 1);
            int availableQuantity = integer(position, "availableQuantity", 0);
            BigDecimal average = positive(position, "averageCost");
            positive(position, "databaseLastPrice");
            BigDecimal mark = positive(position, "markPrice");
            BigDecimal highest = positive(position, "highestPrice");
            requireContract(availableQuantity <= quantity && highest.compareTo(mark) >= 0,
                    "position数量或最高价无效");
            LocalDate markDate = LocalDate.parse(text(position, "markTradeDate"));
            long age = ChronoUnit.DAYS.between(markDate, request.tradeDate());
            requireContract(age >= 0 && age <= 7
                            && integer(position, "priceAgeDays", 0) == age,
                    "position估值日期或价格年龄无效");
            stale |= age >= 4;
            BigDecimal itemMarket = money(number(position, "marketValue"));
            BigDecimal itemPnl = money(number(position, "unrealizedPnl"));
            requireContract(itemMarket.equals(money(mark.multiply(BigDecimal.valueOf(quantity))))
                            && itemPnl.equals(money(
                            mark.subtract(average).multiply(BigDecimal.valueOf(quantity))))
                            && numericallyEqual(
                            number(position, "positionWeight"),
                            ratio(itemMarket, totalAsset)),
                    "position市值、损益或权重重算无效");
            BigDecimal trailingPct = number(position, "trailingStopPct");
            requireContract(trailingPct.signum() >= 0
                            && trailingPct.compareTo(BigDecimal.ONE) < 0
                            && numericallyEqual(
                            positive(position, "trailingStopPrice"),
                            price(highest.multiply(BigDecimal.ONE.subtract(trailingPct)))),
                    "position移动止损重算无效");
            nullablePositive(position.get("stopLoss"));
            nullablePositive(position.get("targetPrice"));
            nullablePositiveLong(position.get("sourcePlanId"));
            nullableDate(position.get("lastBuyDate"));
            positionMarketValues.put(symbol, itemMarket);
            marketSum = marketSum.add(itemMarket);
            pnlSum = pnlSum.add(itemPnl);
        }
        requireContract(positionSymbols.equals(positionSymbols.stream().sorted().toList())
                        && positionSymbols.size() == new HashSet<>(positionSymbols).size()
                        && integer(account, "positionCount", 0) == positionValues.size()
                        && money(marketSum).equals(marketValue)
                        && money(pnlSum).equals(
                        money(number(account, "recomputedUnrealizedPnl"))),
                "position排序、数量或账户汇总无效");

        long previousOrderId = 0;
        int buyCount = 0;
        int sellCount = 0;
        BigDecimal frozenBuySum = BigDecimal.ZERO;
        Map<String, BigDecimal> pendingBySymbol = new HashMap<>();
        Map<String, Integer> pendingSellBySymbol = new HashMap<>();
        for (JsonNode order : nodes(orders)) {
            requireContract(fields(order).equals(ORDER_FIELDS), "pendingOrder字段白名单无效");
            long id = positiveLong(order, "orderId");
            requireContract(id > previousOrderId, "pendingOrder必须按ID升序");
            previousOrderId = id;
            String orderSymbol = symbol(order, "symbol");
            String side = text(order, "side");
            int quantity = integer(order, "quantity", 1);
            BigDecimal limitPrice = positive(order, "limitPrice");
            BigDecimal gross = positive(order, "grossAmount");
            BigDecimal frozenAmount = number(order, "frozenAmount");
            int frozenQuantity = integer(order, "frozenQuantity", 0);
            nullablePositiveLong(order.get("tradePlanId"));
            requireContract(numericallyEqual(
                            gross, money(limitPrice.multiply(BigDecimal.valueOf(quantity))))
                            && frozenAmount.signum() >= 0
                            && frozenQuantity <= quantity,
                    "pendingOrder金额或数量重算无效");
            if ("BUY".equals(side)) {
                buyCount++;
                requireContract(money(frozenAmount).compareTo(money(gross)) >= 0
                                && frozenQuantity == 0,
                        "pending BUY冻结事实无效");
                frozenBuySum = frozenBuySum.add(frozenAmount);
                pendingBySymbol.merge(orderSymbol, gross, BigDecimal::add);
            } else {
                sellCount++;
                pendingSellBySymbol.merge(orderSymbol, frozenQuantity, Integer::sum);
                requireContract("SELL".equals(side)
                                && money(frozenAmount).signum() == 0
                                && frozenQuantity > 0,
                        "pending SELL冻结事实无效");
            }
        }
        BigDecimal pendingExposure = money(
                pendingBySymbol.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
        requireContract(integer(account, "pendingBuyCount", 0) == buyCount
                        && integer(account, "pendingSellCount", 0) == sellCount
                        && money(frozenBuySum).equals(frozenCash)
                        && money(number(account, "pendingBuyExposure")).equals(pendingExposure),
                "pendingOrder账户汇总无效");

        Map<String, JsonNode> positionsBySymbol = new HashMap<>();
        positionValues.forEach(item -> positionsBySymbol.put(text(item, "symbol"), item));
        for (Map.Entry<String, Integer> pendingSell : pendingSellBySymbol.entrySet()) {
            JsonNode position = positionsBySymbol.get(pendingSell.getKey());
            requireContract(position != null
                            && pendingSell.getValue()
                            <= integer(position, "quantity", 1)
                            - integer(position, "availableQuantity", 0),
                    "pending SELL frozen quantity is inconsistent with position");
        }

        TreeSet<String> projectedSymbols = new TreeSet<>(positionSymbols);
        projectedSymbols.addAll(pendingBySymbol.keySet());
        List<JsonNode> projectedValues = nodes(projected);
        requireContract(projectedValues.size() == projectedSymbols.size()
                        && integer(account, "projectedPositionCount", 0)
                        == projectedValues.size(),
                "projectedPositionCount无效");
        int projectedIndex = 0;
        for (String projectedSymbol : projectedSymbols) {
            JsonNode item = projectedValues.get(projectedIndex++);
            requireContract(fields(item).equals(PROJECTED_FIELDS)
                            && projectedSymbol.equals(symbol(item, "symbol")),
                    "projectedPosition字段或顺序无效");
            BigDecimal current = positionMarketValues.getOrDefault(
                    projectedSymbol, BigDecimal.ZERO.setScale(2));
            BigDecimal pending = money(pendingBySymbol.getOrDefault(
                    projectedSymbol, BigDecimal.ZERO));
            BigDecimal value = money(current.add(pending));
            requireContract(requiredBoolean(item, "currentPositionPresent")
                            == positionMarketValues.containsKey(projectedSymbol)
                            && money(number(item, "currentPositionValue")).equals(current)
                            && money(number(item, "pendingBuyExposure")).equals(pending)
                            && money(number(item, "projectedPositionValue")).equals(value)
                            && numericallyEqual(
                            number(item, "projectedPositionWeight"),
                            ratio(value, totalAsset)),
                    "projectedPosition重算无效");
        }
        requireContract(numericallyEqual(
                        number(account, "availableCashRatio"),
                        ratio(availableCash, totalAsset)),
                "availableCashRatio重算无效");

        boolean drawdownAvailable = requiredBoolean(account, "accountDrawdownAvailable");
        boolean dailyLossAvailable = requiredBoolean(account, "dailyLossAvailable");
        requireContract(drawdownAvailable == requiredBoolean(
                        equity, "accountDrawdownAvailable")
                        && dailyLossAvailable == requiredBoolean(
                        equity, "dailyLossAvailable"),
                "权益可用状态不一致");
        BigDecimal drawdown = optionalRatio(account.get("accountDrawdown"), drawdownAvailable);
        BigDecimal dailyLoss = optionalRatio(account.get("dailyLossPct"), dailyLossAvailable);
        requireContract(numericallyEqual(drawdown, optionalRatio(
                        equity.get("accountDrawdown"), drawdownAvailable))
                        && numericallyEqual(dailyLoss, optionalRatio(
                        equity.get("dailyLossPct"), dailyLossAvailable)),
                "权益比例字段不一致");
        BigDecimal peak = optionalPositive(
                equity.get("historicalPeakTotalAsset"), drawdownAvailable);
        BigDecimal previous = optionalPositive(
                equity.get("previousTotalAsset"), dailyLossAvailable);
        LocalDate previousDate = optionalDate(
                equity.get("previousSnapshotDate"), dailyLossAvailable);
        int snapshotCount = integer(equity, "snapshotCount", 0);
        requireContract(snapshotCount > 0 || !drawdownAvailable && !dailyLossAvailable,
                "empty equity history cannot claim available metrics");
        if (drawdownAvailable) {
            BigDecimal expectedDrawdown = totalAsset.compareTo(peak) >= 0
                    ? ZERO : ratio(peak.subtract(totalAsset), peak);
            requireContract(numericallyEqual(expectedDrawdown, drawdown),
                    "accountDrawdown重算无效");
        }
        if (dailyLossAvailable) {
            BigDecimal expectedDailyLoss = totalAsset.compareTo(previous) >= 0
                    ? ZERO : ratio(previous.subtract(totalAsset), previous);
            requireContract(previousDate.isBefore(request.tradeDate())
                            && numericallyEqual(expectedDailyLoss, dailyLoss),
                    "dailyLossPct重算无效");
        }
        boolean partial = !drawdownAvailable || !dailyLossAvailable;
        requireContract(requiredBoolean(completeness, "accountDrawdownAvailable")
                        == drawdownAvailable
                        && requiredBoolean(completeness, "dailyLossAvailable")
                        == dailyLossAvailable
                        && requiredBoolean(completeness, "priceFreshnessWarning") == stale
                        && requiredBoolean(completeness, "partial") == partial,
                "completeness重算无效");

        List<VetoSpec> vetoes = vetoes(
                account, positionValues, projectedValues, maxPositions, maxWeight);
        ScoreFacts score = score(
                account, positionValues, projectedValues, maxPositions, maxWeight,
                stale, partial, !vetoes.isEmpty());
        int confidence = Math.max(40, 100
                - (drawdownAvailable ? 0 : 20)
                - (dailyLossAvailable ? 0 : 20)
                - (stale ? 20 : 0));
        return new ContextFacts(
                true, null, partial, stale, score.warning(), score.score(),
                confidence, List.copyOf(vetoes));
    }

    private static List<VetoSpec> vetoes(
            JsonNode account,
            List<JsonNode> positions,
            List<JsonNode> projected,
            int maxPositions,
            BigDecimal maxWeight
    ) {
        List<VetoSpec> result = new ArrayList<>();
        if (requiredBoolean(account, "accountDrawdownAvailable")) {
            BigDecimal value = number(account, "accountDrawdown");
            if (value.compareTo(new BigDecimal("0.12")) >= 0) {
                result.add(new VetoSpec(
                        "POSITION_RISK_ACCOUNT_DRAWDOWN_LIMIT",
                        "accountId=1 accountDrawdown=%s threshold=0.12"
                                .formatted(plain(value))));
            }
        }
        if (requiredBoolean(account, "dailyLossAvailable")) {
            BigDecimal value = number(account, "dailyLossPct");
            if (value.compareTo(new BigDecimal("0.03")) >= 0) {
                result.add(new VetoSpec(
                        "POSITION_RISK_DAILY_LOSS_LIMIT",
                        "accountId=1 dailyLossPct=%s threshold=0.03"
                                .formatted(plain(value))));
            }
        }
        int projectedCount = integer(account, "projectedPositionCount", 0);
        if (Math.max(integer(account, "positionCount", 0), projectedCount) > maxPositions) {
            result.add(new VetoSpec(
                    "POSITION_RISK_MAX_POSITIONS_EXCEEDED",
                    "accountId=1 projectedPositionCount=%d threshold=%d"
                            .formatted(projectedCount, maxPositions)));
        }
        for (JsonNode position : positions) {
            BigDecimal value = number(position, "positionWeight");
            String symbol = text(position, "symbol");
            if (value.compareTo(maxWeight) > 0) {
                result.add(new VetoSpec(
                        "POSITION_RISK_POSITION_WEIGHT_LIMIT_" + symbol,
                        "symbol=%s positionWeight=%s threshold=%s".formatted(
                                symbol, plain(value), plain(maxWeight))));
            }
        }
        for (JsonNode item : projected) {
            BigDecimal value = number(item, "projectedPositionWeight");
            String symbol = text(item, "symbol");
            if (number(item, "pendingBuyExposure").signum() > 0
                    && value.compareTo(maxWeight) > 0) {
                result.add(new VetoSpec(
                        "POSITION_RISK_PROJECTED_WEIGHT_LIMIT_" + symbol,
                        "symbol=%s projectedPositionWeight=%s threshold=%s".formatted(
                                symbol, plain(value), plain(maxWeight))));
            }
        }
        for (JsonNode position : positions) {
            JsonNode stop = position.get("stopLoss");
            BigDecimal mark = number(position, "markPrice");
            String symbol = text(position, "symbol");
            if (stop != null && !stop.isNull() && mark.compareTo(stop.decimalValue()) <= 0) {
                result.add(new VetoSpec(
                        "POSITION_RISK_STOP_LOSS_TRIGGERED_" + symbol,
                        "symbol=%s markPrice=%s stopLoss=%s".formatted(
                                symbol, plain(mark), plain(stop.decimalValue()))));
            }
        }
        for (JsonNode position : positions) {
            BigDecimal highest = number(position, "highestPrice");
            BigDecimal average = number(position, "averageCost");
            BigDecimal mark = number(position, "markPrice");
            BigDecimal trailing = number(position, "trailingStopPrice");
            String symbol = text(position, "symbol");
            if (highest.compareTo(average) > 0 && mark.compareTo(trailing) <= 0) {
                result.add(new VetoSpec(
                        "POSITION_RISK_TRAILING_STOP_TRIGGERED_" + symbol,
                        "symbol=%s markPrice=%s trailingStopPrice=%s".formatted(
                                symbol, plain(mark), plain(trailing))));
            }
        }
        return result;
    }

    private static ScoreFacts score(
            JsonNode account,
            List<JsonNode> positions,
            List<JsonNode> projected,
            int maxPositions,
            BigDecimal maxWeight,
            boolean stale,
            boolean partial,
            boolean veto
    ) {
        if (veto) return new ScoreFacts(0, true);
        int deduction = 0;
        boolean warning = partial;
        if (requiredBoolean(account, "accountDrawdownAvailable")) {
            BigDecimal value = number(account, "accountDrawdown");
            if (value.compareTo(new BigDecimal("0.08")) >= 0) {
                deduction += 20;
                warning = true;
            } else if (value.compareTo(new BigDecimal("0.05")) >= 0) {
                deduction += 10;
                warning = true;
            }
        }
        if (requiredBoolean(account, "dailyLossAvailable")) {
            BigDecimal value = number(account, "dailyLossPct");
            if (value.compareTo(new BigDecimal("0.02")) >= 0) {
                deduction += 20;
                warning = true;
            } else if (value.compareTo(new BigDecimal("0.01")) >= 0) {
                deduction += 10;
                warning = true;
            }
        }
        if (Math.max(integer(account, "positionCount", 0),
                integer(account, "projectedPositionCount", 0)) == maxPositions) {
            deduction += 10;
            warning = true;
        }
        BigDecimal near = maxWeight.multiply(new BigDecimal("0.9"));
        Set<String> concentrationSymbols = new HashSet<>();
        for (JsonNode item : positions) {
            BigDecimal value = number(item, "positionWeight");
            if (value.compareTo(near) >= 0 && value.compareTo(maxWeight) <= 0) {
                concentrationSymbols.add(text(item, "symbol"));
            }
        }
        for (JsonNode item : projected) {
            BigDecimal value = number(item, "projectedPositionWeight");
            if (number(item, "pendingBuyExposure").signum() > 0
                    && value.compareTo(near) >= 0
                    && value.compareTo(maxWeight) <= 0) {
                concentrationSymbols.add(text(item, "symbol"));
            }
        }
        int concentration = concentrationSymbols.size();
        if (concentration > 0) {
            deduction += Math.min(20, concentration * 10);
            warning = true;
        }
        if (number(account, "availableCashRatio").compareTo(new BigDecimal("0.10")) < 0) {
            deduction += 10;
            warning = true;
        }
        if (stale) {
            deduction += 10;
            warning = true;
        }
        if (positions.stream().anyMatch(item -> {
            JsonNode target = item.get("targetPrice");
            return target != null && !target.isNull()
                    && number(item, "markPrice").compareTo(target.decimalValue()) >= 0;
        })) {
            warning = true;
        }
        return new ScoreFacts(Math.max(0, Math.min(100, 100 - deduction)), warning);
    }

    private static void validateUnavailableRun(AgentOutput run, String expectedErrorCode) {
        require(run.status() == RunStatus.INSUFFICIENT_DATA
                        && run.gateStatus() == GateStatus.NOT_APPLICABLE
                        && run.decision() == RunDecision.NOT_APPLICABLE
                        && !run.veto()
                        && run.score() == 0
                        && run.confidence() == 0
                        && run.findings().isEmpty()
                        && run.evidence().isEmpty()
                        && run.errors().size() == 1
                        && expectedErrorCode.equals(run.errors().get(0).code()),
                "阶段2H不可用或非法POSITION_RISK安全降级无效");
    }

    private static JsonNode object(JsonNode parent, String field, Set<String> expectedFields) {
        JsonNode value = parent.get(field);
        requireContract(value != null && value.isObject()
                        && fields(value).equals(expectedFields),
                field + "对象字段无效");
        return value;
    }

    private static JsonNode array(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        requireContract(value != null && value.isArray(), field + "必须是数组");
        return value;
    }

    private static List<JsonNode> nodes(JsonNode array) {
        List<JsonNode> result = new ArrayList<>();
        array.forEach(result::add);
        return result;
    }

    private static Set<String> fields(JsonNode value) {
        Set<String> result = new HashSet<>();
        value.fieldNames().forEachRemaining(result::add);
        return result;
    }

    private static List<String> strings(JsonNode value) {
        requireContract(value != null && value.isArray(), "字符串数组无效");
        List<String> result = new ArrayList<>();
        value.forEach(item -> {
            requireContract(item.isTextual(), "字符串数组元素无效");
            result.add(item.textValue());
        });
        return result;
    }

    private static String text(JsonNode value, String field) {
        JsonNode item = value.get(field);
        requireContract(item != null && item.isTextual() && notBlank(item.textValue()),
                field + "文本无效");
        return item.textValue();
    }

    private static String symbol(JsonNode value, String field) {
        String result = text(value, field);
        requireContract(result.matches("^[0-9]{6}$"), field + " symbol无效");
        return result;
    }

    private static BigDecimal number(JsonNode value, String field) {
        JsonNode item = value.get(field);
        requireContract(item != null && item.isNumber(), field + "数值无效");
        return item.decimalValue();
    }

    private static BigDecimal positive(JsonNode value, String field) {
        BigDecimal result = number(value, field);
        requireContract(result.signum() > 0, field + "必须大于0");
        return result;
    }

    private static int integer(JsonNode value, String field, int minimum) {
        JsonNode item = value.get(field);
        requireContract(item != null && item.isIntegralNumber()
                        && item.canConvertToInt() && item.intValue() >= minimum,
                field + "整数无效");
        return item.intValue();
    }

    private static long positiveLong(JsonNode value, String field) {
        JsonNode item = value.get(field);
        requireContract(item != null && item.isIntegralNumber()
                        && item.canConvertToLong() && item.longValue() > 0,
                field + "ID无效");
        return item.longValue();
    }

    private static void nullablePositiveLong(JsonNode value) {
        requireContract(value == null || value.isNull()
                        || value.isIntegralNumber() && value.canConvertToLong()
                        && value.longValue() > 0,
                "可空ID无效");
    }

    private static void nullablePositive(JsonNode value) {
        requireContract(value == null || value.isNull()
                        || value.isNumber() && value.decimalValue().signum() > 0,
                "可空正数无效");
    }

    private static void nullableDate(JsonNode value) {
        if (value == null || value.isNull()) return;
        requireContract(value.isTextual(), "可空日期无效");
        LocalDate.parse(value.textValue());
    }

    private static boolean requiredBoolean(JsonNode value, String field) {
        JsonNode item = value.get(field);
        requireContract(item != null && item.isBoolean(), field + "布尔值无效");
        return item.booleanValue();
    }

    private static BigDecimal optionalRatio(JsonNode value, boolean available) {
        if (!available) {
            requireContract(value == null || value.isNull(), "不可用比例必须为null");
            return null;
        }
        requireContract(value != null && value.isNumber()
                        && value.decimalValue().signum() >= 0
                        && value.decimalValue().compareTo(BigDecimal.ONE) <= 0,
                "可用比例无效");
        return value.decimalValue();
    }

    private static BigDecimal optionalPositive(JsonNode value, boolean available) {
        if (!available) {
            requireContract(value == null || value.isNull(), "不可用金额必须为null");
            return null;
        }
        requireContract(value != null && value.isNumber()
                        && value.decimalValue().signum() > 0,
                "可用金额无效");
        return value.decimalValue();
    }

    private static LocalDate optionalDate(JsonNode value, boolean available) {
        if (!available) {
            requireContract(value == null || value.isNull(), "不可用日期必须为null");
            return null;
        }
        requireContract(value != null && value.isTextual(), "可用日期无效");
        return LocalDate.parse(value.textValue());
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal price(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        requireContract(denominator.signum() > 0, "比例分母必须大于0");
        return numerator.divide(denominator, 8, RoundingMode.HALF_UP);
    }

    private static String plain(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.signum() == 0 ? "0" : normalized.toPlainString();
    }

    private static boolean containsExecutionInstruction(String value) {
        if (!notBlank(value)) return true;
        return value.contains("立即买入")
                || value.contains("立即卖出")
                || value.contains("清仓")
                || value.contains("自动下单")
                || value.contains("执行交易")
                || value.contains("收益承诺");
    }

    private static boolean jsonSemanticallyEqual(JsonNode left, JsonNode right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left.isNumber() && right.isNumber()) {
            return left.decimalValue().compareTo(right.decimalValue()) == 0;
        }
        if (left.isObject() && right.isObject()) {
            if (!fields(left).equals(fields(right))) return false;
            var names = left.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!jsonSemanticallyEqual(left.get(name), right.get(name))) return false;
            }
            return true;
        }
        if (left.isArray() && right.isArray()) {
            if (left.size() != right.size()) return false;
            for (int index = 0; index < left.size(); index++) {
                if (!jsonSemanticallyEqual(left.get(index), right.get(index))) return false;
            }
            return true;
        }
        return Objects.equals(left, right);
    }

    private static boolean numericallyEqual(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static void requireContract(boolean condition, String message) {
        if (!condition) throw new ContractException(message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AgentResponseValidationException("智能体响应校验失败：" + message);
        }
    }

    private record VetoSpec(String code, String reason) {
    }

    private record ScoreFacts(int score, boolean warning) {
    }

    private record ContextFacts(
            boolean available,
            String reasonCode,
            boolean partial,
            boolean stale,
            boolean warning,
            int score,
            int confidence,
            List<VetoSpec> vetoes
    ) {
        static ContextFacts unavailable(String reasonCode) {
            return new ContextFacts(
                    false, reasonCode, false, false, false, 0, 0, List.of());
        }
    }

    private static final class ContractException extends RuntimeException {
        private ContractException(String message) {
            super(message);
        }
    }
}

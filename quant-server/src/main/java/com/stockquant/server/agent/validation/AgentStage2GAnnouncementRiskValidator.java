package com.stockquant.server.agent.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.announcement.AnnouncementCanonicalService;
import com.stockquant.server.agent.announcement.AnnouncementCanonicalService.AnnouncementFact;
import com.stockquant.server.agent.announcement.AnnouncementContracts;
import com.stockquant.server.agent.announcement.AnnouncementRiskRules;
import com.stockquant.server.agent.announcement.AnnouncementRiskRules.EventFact;
import com.stockquant.server.agent.announcement.AnnouncementRiskRules.Group;
import com.stockquant.server.agent.announcement.AnnouncementRiskRules.RiskEvent;
import com.stockquant.server.agent.exception.AgentResponseValidationException;
import com.stockquant.server.agent.model.AgentModels.AgentOutput;
import com.stockquant.server.agent.model.AgentModels.AgentTeamRequest;
import com.stockquant.server.agent.model.AgentModels.Evidence;
import com.stockquant.server.agent.model.AgentModels.Finding;
import com.stockquant.server.agent.model.AgentTypes.AgentCode;
import com.stockquant.server.agent.model.AgentTypes.EvidenceCategory;
import com.stockquant.server.agent.model.AgentTypes.EvidenceSourceType;
import com.stockquant.server.agent.model.AgentTypes.GateStatus;
import com.stockquant.server.agent.model.AgentTypes.RunDecision;
import com.stockquant.server.agent.model.AgentTypes.RunStatus;
import com.stockquant.server.agent.model.AgentTypes.Severity;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

final class AgentStage2GAnnouncementRiskValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AnnouncementCanonicalService CANONICAL =
            new AnnouncementCanonicalService(MAPPER);
    private static final Set<String> BASE_FIELDS = Set.of(
            "available", "queriedAt", "queryScope", "producer", "producerVersion",
            "contextProfile", "schemaVersion", "symbol", "requestTradeDate",
            "marketTimezone", "knowledgeCutoff", "lookbackStartDate", "lookbackDays",
            "sourceCode", "providerContractVersion", "assuranceLevel", "formalEligible",
            "pitVerified", "revisionRelationshipGuaranteed",
            "reportedPublishTimePrecision", "completeCapture", "captureBatchVersion",
            "captureObservedAt", "captureAgeHours", "eventCount", "events", "limitations"
    );
    private static final Set<String> EVENT_FIELDS = Set.of(
            "sourceAnnouncementId", "sourceIdentityStrength", "symbol", "securityName",
            "title", "reportedPublishDate", "reportedPublishTimePrecision", "sourceUrl",
            "normalizedSourceUrl", "sourceUrlHash", "firstObservedAt", "knownAt",
            "canonicalContentHash", "observationVersion", "sourceCode",
            "providerContractVersion", "assuranceLevel", "formalEligible", "pitVerified",
            "revisionRelationshipGuaranteed"
    );

    private AgentStage2GAnnouncementRiskValidator() {
    }

    static void validate(
            AgentTeamRequest request,
            AgentOutput run,
            AgentOutput dataQuality
    ) {
        require(run.agentCode() == AgentCode.ANNOUNCEMENT_RISK,
                "阶段2G校验目标必须是ANNOUNCEMENT_RISK");
        require(!run.veto(), "阶段2G ANNOUNCEMENT_RISK不得产生正式veto");
        if (dataQuality.gateStatus() == GateStatus.BLOCKED) {
            require(run.status() == RunStatus.INSUFFICIENT_DATA
                            && run.gateStatus() == GateStatus.NOT_APPLICABLE
                            && run.decision() == RunDecision.NOT_APPLICABLE
                            && run.score() == 0
                            && run.confidence() == 0
                            && run.findings().isEmpty()
                            && run.evidence().isEmpty()
                            && run.errors().isEmpty(),
                    "阶段2G DATA_QUALITY阻断时公告风险必须安全降级");
            return;
        }

        ContextFacts facts;
        try {
            facts = parseContext(request);
        } catch (ContractException error) {
            validateUnavailable(run, AnnouncementContracts.INPUT_INVALID);
            return;
        }
        if (!facts.available()) {
            validateUnavailable(run, facts.reasonCode());
            return;
        }

        AnnouncementRiskRules.Evaluation expected =
                AnnouncementRiskRules.evaluate(facts.events(), request.tradeDate());
        boolean hasRisk = !expected.riskEvents().isEmpty();
        require(run.status() == RunStatus.COMPLETED
                        && run.gateStatus() == (hasRisk ? GateStatus.WARN : GateStatus.PASS)
                        && run.decision() == (hasRisk ? RunDecision.WARN : RunDecision.PASS)
                        && run.score() == expected.score()
                        && run.confidence() == 40
                        && run.errors().isEmpty(),
                "阶段2G有效公告风险状态、score或confidence不一致");

        String coverageId = "announcement-risk-coverage-" + request.contextHash();
        require(run.evidence().size() == expected.riskEvents().size() + 1,
                "阶段2G公告evidence数量不一致");
        Evidence coverage = run.evidence().get(0);
        require(coverageId.equals(coverage.evidenceId())
                        && coverage.category() == EvidenceCategory.QUERY_RESULT
                        && coverage.sourceType() == EvidenceSourceType.JAVA_ENGINE
                        && AnnouncementContracts.PRODUCER.equals(coverage.sourceName())
                        && "contextSnapshot.securityEvents".equals(coverage.sourceRef())
                        && Objects.equals(coverage.symbol(), request.symbol())
                        && Objects.equals(coverage.tradeDate(), request.tradeDate())
                        && Objects.equals(coverage.observedAt(), facts.captureObservedAt())
                        && Objects.equals(coverage.contentHash(), request.contextHash())
                        && fields(coverage.fields()).equals(Set.of("securityEvents"))
                        && jsonSemanticallyEqual(
                        coverage.fields().get("securityEvents"),
                        request.contextSnapshot().get("securityEvents")),
                "阶段2G coverage evidence无效");

        for (int index = 0; index < expected.riskEvents().size(); index++) {
            RiskEvent risk = expected.riskEvents().get(index);
            Evidence evidence = run.evidence().get(index + 1);
            EventFact event = risk.event();
            String evidenceId = "announcement-risk-event-" + event.observationVersion();
            require(evidenceId.equals(evidence.evidenceId())
                            && evidence.category() == EvidenceCategory.SECURITY_EVENT
                            && evidence.sourceType() == EvidenceSourceType.JAVA_ENGINE
                            && AnnouncementContracts.PRODUCER.equals(evidence.sourceName())
                            && ("contextSnapshot.securityEvents.events."
                            + event.observationVersion()).equals(evidence.sourceRef())
                            && Objects.equals(evidence.symbol(), request.symbol())
                            && Objects.equals(evidence.tradeDate(), request.tradeDate())
                            && Objects.equals(evidence.observedAt(), event.knownAt())
                            && Objects.equals(evidence.contentHash(),
                            event.canonicalContentHash())
                            && fields(evidence.fields()).equals(Set.of("event"))
                            && jsonSemanticallyEqual(
                            evidence.fields().get("event"),
                            facts.eventNodesByVersion().get(event.observationVersion())),
                    "阶段2G event evidence身份、投影或Hash无效");
        }

        validateFindings(request, run.findings(), coverageId, expected.riskEvents());
        String expectedSummary = hasRisk
                ? "当前完整抓取范围命中" + expected.riskEvents().size()
                + "条冻结标题风险事件；结果仅用于研究提示。"
                : "在当前完整抓取范围内未匹配冻结标题规则；结果仅用于研究提示。";
        require(expectedSummary.equals(run.summary())
                        && !containsExecutionInstruction(run.summary()),
                "阶段2G公告风险摘要不一致或包含交易指令");
    }

    private static ContextFacts parseContext(AgentTeamRequest request) {
        JsonNode context = request.contextSnapshot().get("securityEvents");
        requireContract(context != null && context.isObject(),
                "securityEvents必须是对象");
        boolean available = bool(context, "available");
        Set<String> expectedFields = new HashSet<>(BASE_FIELDS);
        if (!available) {
            expectedFields.add("reasonCode");
            expectedFields.add("reason");
        }
        requireContract(fields(context).equals(expectedFields),
                "securityEvents字段白名单无效");
        JsonNode scope = object(context, "queryScope", Set.of("symbol", "tradeDate"));
        requireContract(
                AnnouncementContracts.PRODUCER.equals(text(context, "producer"))
                        && AnnouncementContracts.PRODUCER_VERSION.equals(
                        text(context, "producerVersion"))
                        && AnnouncementContracts.CONTEXT_PROFILE.equals(
                        text(context, "contextProfile"))
                        && AnnouncementContracts.CONTEXT_SCHEMA_VERSION.equals(
                        text(context, "schemaVersion"))
                        && request.symbol().equals(text(context, "symbol"))
                        && request.tradeDate().toString().equals(
                        text(context, "requestTradeDate"))
                        && AnnouncementContracts.MARKET_TIMEZONE.equals(
                        text(context, "marketTimezone"))
                        && request.symbol().equals(text(scope, "symbol"))
                        && request.tradeDate().toString().equals(
                        text(scope, "tradeDate"))
                        && request.tradeDate().minusDays(
                        AnnouncementContracts.LOOKBACK_DAYS - 1L).toString()
                        .equals(text(context, "lookbackStartDate"))
                        && integer(context, "lookbackDays")
                        == AnnouncementContracts.LOOKBACK_DAYS
                        && AnnouncementContracts.SOURCE_CODE.equals(
                        text(context, "sourceCode"))
                        && AnnouncementContracts.PROVIDER_CONTRACT_VERSION.equals(
                        text(context, "providerContractVersion"))
                        && AnnouncementContracts.ASSURANCE_LEVEL.equals(
                        text(context, "assuranceLevel"))
                        && !bool(context, "formalEligible")
                        && !bool(context, "pitVerified")
                        && !bool(context, "revisionRelationshipGuaranteed")
                        && AnnouncementContracts.PUBLISH_TIME_PRECISION.equals(
                        text(context, "reportedPublishTimePrecision"))
                        && strings(context.get("limitations")).equals(
                        AnnouncementContracts.LIMITATIONS),
                "securityEvents固定来源或范围契约无效");
        Instant queriedAt = instant(context, "queriedAt");
        Instant cutoff = instant(context, "knowledgeCutoff");
        LocalDate currentDate = queriedAt.atZone(
                AnnouncementContracts.MARKET_ZONE).toLocalDate();
        Instant expectedCutoff = request.tradeDate().isBefore(currentDate)
                ? request.tradeDate().atTime(LocalTime.MAX)
                .atZone(AnnouncementContracts.MARKET_ZONE).toInstant()
                .truncatedTo(ChronoUnit.MICROS)
                : queriedAt.truncatedTo(ChronoUnit.MICROS);
        requireContract(cutoff.equals(expectedCutoff),
                "securityEvents knowledgeCutoff无效");

        JsonNode eventArray = context.get("events");
        requireContract(eventArray != null && eventArray.isArray()
                        && integer(context, "eventCount") == eventArray.size(),
                "securityEvents事件计数无效");
        if (!available) {
            String reasonCode = text(context, "reasonCode");
            requireContract(AnnouncementContracts.UNAVAILABLE_REASON_CODES.contains(
                            reasonCode)
                            && notBlank(text(context, "reason"))
                            && !bool(context, "completeCapture")
                            && context.get("captureBatchVersion").isNull()
                            && context.get("captureObservedAt").isNull()
                            && context.get("captureAgeHours").isNull()
                            && eventArray.isEmpty(),
                    "不可用securityEvents语义无效");
            return ContextFacts.unavailable(reasonCode);
        }

        requireContract(bool(context, "completeCapture")
                        && text(context, "captureBatchVersion")
                        .startsWith("ANNOUNCEMENT_BATCH_V1:")
                        && context.get("captureAgeHours").isNumber()
                        && context.get("captureAgeHours").decimalValue().signum() >= 0
                        && context.get("captureAgeHours").decimalValue()
                        .compareTo(BigDecimal.valueOf(24)) <= 0,
                "securityEvents完整覆盖元数据无效");
        Instant captureObservedAt = instant(context, "captureObservedAt");
        Duration captureAge = Duration.between(captureObservedAt, cutoff);
        BigDecimal expectedCaptureAgeHours =
                BigDecimal.valueOf(captureAge.toMillis())
                        .divide(
                                BigDecimal.valueOf(3_600_000L),
                                6,
                                java.math.RoundingMode.HALF_UP);
        requireContract(!request.tradeDate().isAfter(currentDate)
                        && !captureAge.isNegative()
                        && captureAge.compareTo(Duration.ofHours(24)) <= 0
                        && context.get("captureAgeHours").decimalValue()
                        .compareTo(expectedCaptureAgeHours) == 0,
                "securityEvents抓取时间无效");

        List<EventFact> events = new ArrayList<>();
        java.util.Map<String, JsonNode> nodes = new java.util.HashMap<>();
        Set<String> identities = new HashSet<>();
        Set<String> versions = new HashSet<>();
        LocalDate lookbackStart = request.tradeDate().minusDays(
                AnnouncementContracts.LOOKBACK_DAYS - 1L);
        for (JsonNode event : eventArray) {
            requireContract(event.isObject() && fields(event).equals(EVENT_FIELDS),
                    "securityEvents事件字段白名单无效");
            String sourceId = text(event, "sourceAnnouncementId");
            String strength = text(event, "sourceIdentityStrength");
            String sourceUrl = text(event, "sourceUrl");
            AnnouncementCanonicalService.SourceIdentity sourceIdentity =
                    CANONICAL.sourceIdentity(sourceUrl);
            LocalDate reportedDate = LocalDate.parse(
                    text(event, "reportedPublishDate"));
            Instant firstObserved = instant(event, "firstObservedAt");
            Instant knownAt = instant(event, "knownAt");
            String contentHash = text(event, "canonicalContentHash");
            String version = text(event, "observationVersion");
            requireContract(
                    identities.add(sourceId)
                            && versions.add(version)
                            && sourceId.equals(sourceIdentity.sourceAnnouncementId())
                            && strength.equals(sourceIdentity.strength())
                            && sourceIdentity.normalizedUrl().equals(
                            text(event, "normalizedSourceUrl"))
                            && AnnouncementCanonicalService.sha256(
                            sourceIdentity.normalizedUrl()).equals(
                            text(event, "sourceUrlHash"))
                            && request.symbol().equals(text(event, "symbol"))
                            && notBlank(text(event, "securityName"))
                            && text(event, "securityName").length() <= 128
                            && notBlank(text(event, "title"))
                            && text(event, "title").length() <= 1024
                            && !reportedDate.isBefore(lookbackStart)
                            && reportedDate.compareTo(request.tradeDate()) <= 0
                            && !reportedDate.isAfter(firstObserved.atZone(
                            AnnouncementContracts.MARKET_ZONE).toLocalDate())
                            && firstObserved.equals(knownAt)
                            && !knownAt.isAfter(cutoff)
                            && contentHash.matches("^[0-9a-f]{64}$")
                            && version.matches("^[0-9a-f]{64}$")
                            && AnnouncementContracts.SOURCE_CODE.equals(
                            text(event, "sourceCode"))
                            && AnnouncementContracts.PROVIDER_CONTRACT_VERSION.equals(
                            text(event, "providerContractVersion"))
                            && AnnouncementContracts.ASSURANCE_LEVEL.equals(
                            text(event, "assuranceLevel"))
                            && !bool(event, "formalEligible")
                            && !bool(event, "pitVerified")
                            && !bool(event, "revisionRelationshipGuaranteed")
                            && AnnouncementContracts.PUBLISH_TIME_PRECISION.equals(
                            text(event, "reportedPublishTimePrecision")),
                    "securityEvents事件身份、来源或时间无效");
            AnnouncementFact fact = new AnnouncementFact(
                    request.symbol(),
                    text(event, "securityName"),
                    text(event, "title"),
                    reportedDate,
                    sourceUrl,
                    text(event, "normalizedSourceUrl"),
                    text(event, "sourceUrlHash"),
                    sourceId,
                    strength,
                    firstObserved,
                    contentHash,
                    version,
                    MAPPER.createObjectNode());
            requireContract(CANONICAL.hashMatches(fact),
                    "securityEvents canonical Hash无效");
            events.add(new EventFact(
                    sourceId,
                    strength,
                    request.symbol(),
                    fact.securityName(),
                    fact.title(),
                    reportedDate,
                    sourceUrl,
                    firstObserved,
                    knownAt,
                    contentHash,
                    version));
            nodes.put(version, event);
        }
        Comparator<EventFact> order = Comparator
                .comparing(EventFact::reportedPublishDate).reversed()
                .thenComparing(EventFact::knownAt, Comparator.reverseOrder())
                .thenComparing(EventFact::sourceAnnouncementId)
                .thenComparing(EventFact::observationVersion);
        requireContract(events.equals(events.stream().sorted(order).toList()),
                "securityEvents事件排序无效");
        return new ContextFacts(
                true,
                null,
                captureObservedAt,
                List.copyOf(events),
                java.util.Map.copyOf(nodes));
    }

    private static void validateFindings(
            AgentTeamRequest request,
            List<Finding> findings,
            String coverageId,
            List<RiskEvent> risks
    ) {
        require(findings.size() == 5, "阶段2G必须固定输出五类finding");
        List<Group> groups = java.util.Arrays.asList(
                null,
                Group.REGULATORY_DELISTING,
                Group.FINANCIAL_LITIGATION,
                Group.OWNERSHIP_OPERATION,
                Group.RESEARCH_LIMITATIONS);
        List<String> titles = List.of(
                "来源覆盖与资格",
                "监管、退市和风险警示",
                "财务、债务和诉讼",
                "股东、减持、质押、担保及经营风险",
                "时效性、修订限制和研究边界");
        for (int index = 0; index < findings.size(); index++) {
            Finding finding = findings.get(index);
            String code = AnnouncementContracts.FINDING_CODES.get(index);
            String expectedId = "announcement-risk-finding-%02d-%s-%s".formatted(
                    index + 1,
                    code.toLowerCase().replace('_', '-'),
                    request.contextHash());
            Group group = groups.get(index);
            List<String> references = new ArrayList<>();
            references.add(coverageId);
            if (group != null) {
                risks.stream()
                        .filter(value -> value.match().groups().contains(group))
                        .map(value -> "announcement-risk-event-"
                                + value.event().observationVersion())
                        .forEach(references::add);
            }
            references = references.stream().distinct().toList();
            Severity expectedSeverity = index == 0
                    ? Severity.INFO
                    : index == 4
                    ? Severity.WARN
                    : AnnouncementRiskRules.groupSeverity(risks, group);
            require(code.equals(finding.code())
                            && expectedId.equals(finding.findingId())
                            && titles.get(index).equals(finding.title())
                            && finding.severity() == expectedSeverity
                            && finding.evidenceIds().equals(references)
                            && notBlank(finding.detail())
                            && !containsExecutionInstruction(finding.detail()),
                    "阶段2G finding代码、顺序、ID、severity或证据引用无效");
        }
    }

    private static void validateUnavailable(AgentOutput run, String errorCode) {
        require(run.status() == RunStatus.INSUFFICIENT_DATA
                        && run.gateStatus() == GateStatus.NOT_APPLICABLE
                        && run.decision() == RunDecision.NOT_APPLICABLE
                        && !run.veto()
                        && run.score() == 0
                        && run.confidence() == 0
                        && run.findings().isEmpty()
                        && run.evidence().isEmpty()
                        && run.errors().size() == 1
                        && errorCode.equals(run.errors().get(0).code()),
                "阶段2G不可用或非法ANNOUNCEMENT_RISK安全降级无效");
    }

    private static JsonNode object(
            JsonNode parent,
            String field,
            Set<String> expectedFields
    ) {
        JsonNode value = parent.get(field);
        requireContract(value != null && value.isObject()
                        && fields(value).equals(expectedFields),
                field + "对象字段无效");
        return value;
    }

    private static Set<String> fields(JsonNode value) {
        Set<String> result = new HashSet<>();
        value.fieldNames().forEachRemaining(result::add);
        return result;
    }

    private static String text(JsonNode value, String field) {
        JsonNode item = value.get(field);
        requireContract(item != null && item.isTextual()
                        && notBlank(item.textValue()),
                field + "文本无效");
        return item.textValue();
    }

    private static boolean bool(JsonNode value, String field) {
        JsonNode item = value.get(field);
        requireContract(item != null && item.isBoolean(), field + "布尔值无效");
        return item.booleanValue();
    }

    private static int integer(JsonNode value, String field) {
        JsonNode item = value.get(field);
        requireContract(item != null && item.isIntegralNumber()
                        && item.canConvertToInt() && item.intValue() >= 0,
                field + "整数无效");
        return item.intValue();
    }

    private static Instant instant(JsonNode value, String field) {
        try {
            return Instant.parse(text(value, field));
        } catch (RuntimeException error) {
            throw new ContractException(field + "时间无效");
        }
    }

    private static List<String> strings(JsonNode value) {
        requireContract(value != null && value.isArray(), "字符串数组无效");
        List<String> result = new ArrayList<>();
        value.forEach(item -> {
            requireContract(item.isTextual(), "字符串数组元素无效");
            result.add(item.textValue());
        });
        return List.copyOf(result);
    }

    private static boolean containsExecutionInstruction(String value) {
        if (!notBlank(value)) return true;
        return value.contains("立即买入")
                || value.contains("立即卖出")
                || value.contains("清仓")
                || value.contains("加仓")
                || value.contains("减仓")
                || value.contains("自动下单")
                || value.contains("收益承诺");
    }

    private static boolean jsonSemanticallyEqual(JsonNode left, JsonNode right) {
        if (left == null || right == null) return left == right;
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

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static void requireContract(boolean condition, String message) {
        if (!condition) throw new ContractException(message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AgentResponseValidationException(
                    "智能体响应校验失败：" + message);
        }
    }

    private record ContextFacts(
            boolean available,
            String reasonCode,
            Instant captureObservedAt,
            List<EventFact> events,
            java.util.Map<String, JsonNode> eventNodesByVersion
    ) {
        static ContextFacts unavailable(String reasonCode) {
            return new ContextFacts(
                    false, reasonCode, null, List.of(), java.util.Map.of());
        }
    }

    private static final class ContractException extends RuntimeException {
        private ContractException(String message) {
            super(message);
        }
    }
}

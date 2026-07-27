package com.stockquant.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.announcement.AnnouncementCanonicalService;
import com.stockquant.server.agent.announcement.AnnouncementCanonicalService.AnnouncementFact;
import com.stockquant.server.agent.announcement.AnnouncementContracts;
import com.stockquant.server.agent.announcement.AnnouncementProviderModels.ProviderRecord;
import com.stockquant.server.agent.model.AgentModels.AgentTeamRequest;
import com.stockquant.server.agent.service.AgentContextHashService;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

final class AgentStage2GTestFixtures {

    enum Scenario {
        NO_EVENT,
        WARN_RISK,
        MULTI_RISK,
        UNAVAILABLE,
        INVALID_HASH,
        INVALID_SOURCE_URL,
        MIXED_EXCLUSION_RISKS,
        POSITION_VETO,
        DATA_QUALITY_BLOCKED_WITH_VETO
    }

    private static final ObjectMapper MAPPER =
            new ObjectMapper().findAndRegisterModules();
    private static final AgentContextHashService CONTEXT_HASHES =
            new AgentContextHashService(MAPPER);
    private static final AnnouncementCanonicalService CANONICAL =
            new AnnouncementCanonicalService(MAPPER);
    private static final String BATCH =
            "ANNOUNCEMENT_BATCH_V1:22222222-2222-2222-2222-222222222222";

    private AgentStage2GTestFixtures() {
    }

    static AgentTeamRequest request(Scenario scenario) {
        AgentStage2HTestFixtures.Scenario baseScenario = switch (scenario) {
            case POSITION_VETO -> AgentStage2HTestFixtures.Scenario.SINGLE_VETO;
            case DATA_QUALITY_BLOCKED_WITH_VETO ->
                    AgentStage2HTestFixtures.Scenario.DATA_QUALITY_BLOCKED_WITH_VETO;
            default -> AgentStage2HTestFixtures.Scenario.PASS;
        };
        AgentTeamRequest base = AgentStage2HTestFixtures.request(baseScenario);
        ObjectNode snapshot = base.contextSnapshot().deepCopy();
        ObjectNode events = switch (scenario) {
            case UNAVAILABLE -> unavailable(
                    base.symbol(), base.tradeDate(),
                    AnnouncementContracts.NO_COMPLETE_CAPTURE);
            default -> available(base.symbol(), base.tradeDate());
        };
        if (scenario == Scenario.MULTI_RISK) {
            addEvent(
                    events,
                    base.symbol(),
                    base.tradeDate(),
                    "1212345678",
                    "立案调查暨重大诉讼公告",
                    base.tradeDate());
            addEvent(
                    events,
                    base.symbol(),
                    base.tradeDate(),
                    "1212345679",
                    "减持计划公告",
                    base.tradeDate().minusDays(10));
        }
        if (scenario == Scenario.WARN_RISK) {
            addEvent(
                    events,
                    base.symbol(),
                    base.tradeDate(),
                    "1212345677",
                    "减持计划公告",
                    base.tradeDate());
        }
        if (scenario == Scenario.INVALID_HASH) {
            addEvent(
                    events,
                    base.symbol(),
                    base.tradeDate(),
                    "1212345680",
                    "问询函",
                    base.tradeDate().minusDays(1));
            ((ObjectNode) events.withArray("events").get(0))
                    .put("canonicalContentHash", "0".repeat(64));
        }
        if (scenario == Scenario.INVALID_SOURCE_URL) {
            addEvent(
                    events,
                    base.symbol(),
                    base.tradeDate(),
                    "1212345680",
                    "问询函",
                    base.tradeDate().minusDays(1));
            ((ObjectNode) events.withArray("events").get(0))
                    .put("sourceUrl", "https://example.com/notice.pdf");
        }
        if (scenario == Scenario.MIXED_EXCLUSION_RISKS) {
            addEvent(
                    events,
                    base.symbol(),
                    base.tradeDate(),
                    "1212345681",
                    "关于撤销退市风险警示并继续实施其他风险警示的公告",
                    base.tradeDate());
            addEvent(
                    events,
                    base.symbol(),
                    base.tradeDate(),
                    "1212345682",
                    "关于股份解除冻结及新增股份冻结的公告",
                    base.tradeDate());
            addEvent(
                    events,
                    base.symbol(),
                    base.tradeDate(),
                    "1212345683",
                    "关于解除股份质押及新增股份质押的公告",
                    base.tradeDate());
        }
        snapshot.set("securityEvents", events);
        String contextHash = CONTEXT_HASHES.hash(snapshot);
        return new AgentTeamRequest(
                base.schemaVersion(),
                base.taskId(),
                base.runIds(),
                base.symbol(),
                base.tradeDate(),
                contextHash,
                base.contextSchemaVersion(),
                AnnouncementContracts.RULE_VERSION,
                base.executionMode(),
                snapshot,
                base.requestedAt());
    }

    private static ObjectNode available(String symbol, LocalDate tradeDate) {
        Instant queriedAt = tradeDate.atTime(LocalTime.NOON)
                .atZone(AnnouncementContracts.MARKET_ZONE).toInstant();
        Instant observedAt = queriedAt.minusSeconds(3600);
        ObjectNode root = base(symbol, tradeDate, queriedAt);
        root.put("available", true);
        root.put("completeCapture", true);
        root.put("captureBatchVersion", BATCH);
        root.put("captureObservedAt", observedAt.toString());
        root.put("captureAgeHours", 1);
        return root;
    }

    private static ObjectNode unavailable(
            String symbol,
            LocalDate tradeDate,
            String reasonCode
    ) {
        Instant queriedAt = tradeDate.atTime(LocalTime.NOON)
                .atZone(AnnouncementContracts.MARKET_ZONE).toInstant();
        ObjectNode root = base(symbol, tradeDate, queriedAt);
        root.put("available", false);
        root.put("reasonCode", reasonCode);
        root.put("reason", "stable unavailable test fixture");
        return root;
    }

    private static ObjectNode base(
            String symbol,
            LocalDate tradeDate,
            Instant queriedAt
    ) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("available", false);
        root.put("queriedAt", queriedAt.toString());
        ObjectNode scope = root.putObject("queryScope");
        scope.put("symbol", symbol);
        scope.put("tradeDate", tradeDate.toString());
        root.put("producer", AnnouncementContracts.PRODUCER);
        root.put("producerVersion", AnnouncementContracts.PRODUCER_VERSION);
        root.put("contextProfile", AnnouncementContracts.CONTEXT_PROFILE);
        root.put("schemaVersion", AnnouncementContracts.CONTEXT_SCHEMA_VERSION);
        root.put("symbol", symbol);
        root.put("requestTradeDate", tradeDate.toString());
        root.put("marketTimezone", AnnouncementContracts.MARKET_TIMEZONE);
        root.put("knowledgeCutoff", queriedAt.toString());
        root.put("lookbackStartDate", tradeDate.minusDays(179).toString());
        root.put("lookbackDays", 180);
        root.put("sourceCode", AnnouncementContracts.SOURCE_CODE);
        root.put("providerContractVersion",
                AnnouncementContracts.PROVIDER_CONTRACT_VERSION);
        root.put("assuranceLevel", AnnouncementContracts.ASSURANCE_LEVEL);
        root.put("formalEligible", false);
        root.put("pitVerified", false);
        root.put("revisionRelationshipGuaranteed", false);
        root.put("reportedPublishTimePrecision",
                AnnouncementContracts.PUBLISH_TIME_PRECISION);
        root.put("completeCapture", false);
        root.putNull("captureBatchVersion");
        root.putNull("captureObservedAt");
        root.putNull("captureAgeHours");
        root.put("eventCount", 0);
        root.putArray("events");
        ArrayNode limitations = root.putArray("limitations");
        AnnouncementContracts.LIMITATIONS.forEach(limitations::add);
        return root;
    }

    private static void addEvent(
            ObjectNode root,
            String symbol,
            LocalDate requestDate,
            String id,
            String title,
            LocalDate reportedDate
    ) {
        Instant observedAt = requestDate.atTime(10, 0)
                .atZone(AnnouncementContracts.MARKET_ZONE).toInstant();
        String url = "https://static.cninfo.com.cn/finalpage/"
                + reportedDate + "/" + id + ".pdf";
        ObjectNode raw = MAPPER.createObjectNode();
        raw.put("代码", symbol);
        raw.put("简称", "平安银行");
        raw.put("公告标题", title);
        raw.put("公告时间", reportedDate.toString());
        raw.put("公告链接", url);
        AnnouncementFact fact = CANONICAL.prepare(
                new ProviderRecord(
                        symbol,
                        "平安银行",
                        title,
                        reportedDate,
                        url,
                        raw),
                observedAt,
                BATCH);
        ObjectNode event = root.withArray("events").addObject();
        event.put("sourceAnnouncementId", fact.sourceAnnouncementId());
        event.put("sourceIdentityStrength", fact.sourceIdentityStrength());
        event.put("symbol", fact.symbol());
        event.put("securityName", fact.securityName());
        event.put("title", fact.title());
        event.put("reportedPublishDate", fact.reportedPublishDate().toString());
        event.put("reportedPublishTimePrecision",
                AnnouncementContracts.PUBLISH_TIME_PRECISION);
        event.put("sourceUrl", fact.sourceUrl());
        event.put("normalizedSourceUrl", fact.normalizedSourceUrl());
        event.put("sourceUrlHash", fact.sourceUrlHash());
        event.put("firstObservedAt", fact.firstObservedAt().toString());
        event.put("knownAt", fact.firstObservedAt().toString());
        event.put("canonicalContentHash", fact.canonicalContentHash());
        event.put("observationVersion", fact.observationVersion());
        event.put("sourceCode", AnnouncementContracts.SOURCE_CODE);
        event.put("providerContractVersion",
                AnnouncementContracts.PROVIDER_CONTRACT_VERSION);
        event.put("assuranceLevel", AnnouncementContracts.ASSURANCE_LEVEL);
        event.put("formalEligible", false);
        event.put("pitVerified", false);
        event.put("revisionRelationshipGuaranteed", false);
        root.put("eventCount", root.withArray("events").size());
    }
}

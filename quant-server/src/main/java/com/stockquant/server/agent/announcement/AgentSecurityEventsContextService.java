package com.stockquant.server.agent.announcement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.announcement.AnnouncementCanonicalService.AnnouncementFact;
import com.stockquant.server.agent.announcement.AnnouncementCanonicalService.SourceIdentity;
import com.stockquant.server.agent.announcement.AnnouncementRepository.CaptureBatchRecord;
import com.stockquant.server.agent.announcement.AnnouncementRepository.ObservationRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class AgentSecurityEventsContextService {

    private final ObjectMapper objectMapper;
    private final AnnouncementRepository repository;
    private final AnnouncementCanonicalService canonicalService;

    public AgentSecurityEventsContextService(
            ObjectMapper objectMapper,
            AnnouncementRepository repository,
            AnnouncementCanonicalService canonicalService
    ) {
        this.objectMapper = objectMapper;
        this.repository = repository;
        this.canonicalService = canonicalService;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ObjectNode create(
            String symbol,
            LocalDate requestTradeDate,
            Instant queriedAt
    ) {
        LocalDate currentDate = queriedAt.atZone(
                AnnouncementContracts.MARKET_ZONE).toLocalDate();
        Instant knowledgeCutoff = requestTradeDate.isBefore(currentDate)
                ? requestTradeDate.atTime(LocalTime.MAX)
                .atZone(AnnouncementContracts.MARKET_ZONE)
                .toInstant()
                .truncatedTo(ChronoUnit.MICROS)
                : queriedAt.truncatedTo(ChronoUnit.MICROS);
        LocalDate lookbackStart = requestTradeDate.minusDays(
                AnnouncementContracts.LOOKBACK_DAYS - 1L);
        ObjectNode context = base(
                symbol, requestTradeDate, queriedAt, knowledgeCutoff, lookbackStart);
        if (requestTradeDate.isAfter(currentDate)) {
            return unavailable(
                    context,
                    AnnouncementContracts.FUTURE_REQUEST_DATE,
                    "公告风险不允许使用未来requestTradeDate");
        }

        List<CaptureBatchRecord> batches = repository.findBatches(
                symbol, knowledgeCutoff);
        if (batches.isEmpty()
                || batches.stream().noneMatch(CaptureBatchRecord::complete)) {
            return unavailable(
                    context,
                    AnnouncementContracts.NO_COMPLETE_CAPTURE,
                    "knowledgeCutoff前没有完整公告抓取批次");
        }
        List<CaptureBatchRecord> complete = batches.stream()
                .filter(CaptureBatchRecord::complete)
                .toList();
        if (complete.stream().anyMatch(value -> !validSource(value, symbol))) {
            return unavailable(
                    context,
                    AnnouncementContracts.SOURCE_UNVERIFIABLE,
                    "公告批次来源或Provider契约不可验证");
        }
        CaptureBatchRecord capture = complete.stream()
                .filter(value -> !value.requestedStartDate().isAfter(lookbackStart)
                        && !value.requestedEndDate().isBefore(requestTradeDate))
                .findFirst()
                .orElse(null);
        if (capture == null) {
            return unavailable(
                    context,
                    AnnouncementContracts.CAPTURE_RANGE_INCOMPLETE,
                    "完整公告批次未覆盖固定180日窗口");
        }
        Duration age = Duration.between(capture.observedAt(), knowledgeCutoff);
        if (age.isNegative()
                || age.compareTo(Duration.ofHours(
                AnnouncementContracts.MAX_CAPTURE_AGE_HOURS)) > 0) {
            return unavailable(
                    context,
                    AnnouncementContracts.CAPTURE_STALE,
                    "公告完整抓取批次距离knowledgeCutoff超过24小时");
        }

        List<ObservationRecord> events = repository.findAsOf(
                symbol, lookbackStart, requestTradeDate, knowledgeCutoff);
        try {
            validateEvents(
                    events,
                    symbol,
                    lookbackStart,
                    requestTradeDate,
                    knowledgeCutoff);
        } catch (IllegalArgumentException error) {
            return unavailable(
                    context,
                    AnnouncementContracts.CONTEXT_INVALID,
                    "公告观察版本未通过Java上下文校验");
        }
        context.put("available", true);
        context.put("completeCapture", true);
        context.put("captureBatchVersion", capture.batchVersion());
        context.put("captureObservedAt", capture.observedAt().toString());
        context.put("captureAgeHours", BigDecimal.valueOf(age.toMillis())
                .divide(BigDecimal.valueOf(3_600_000L), 6, RoundingMode.HALF_UP));
        context.put("eventCount", events.size());
        ArrayNode nodes = context.putArray("events");
        events.forEach(value -> nodes.add(eventNode(value)));
        return context;
    }

    private void validateEvents(
            List<ObservationRecord> events,
            String symbol,
            LocalDate lookbackStart,
            LocalDate requestTradeDate,
            Instant knowledgeCutoff
    ) {
        Comparator<ObservationRecord> order = Comparator
                .comparing(ObservationRecord::reportedPublishDate)
                .reversed()
                .thenComparing(ObservationRecord::knownAt, Comparator.reverseOrder())
                .thenComparing(ObservationRecord::sourceAnnouncementId)
                .thenComparing(ObservationRecord::observationVersion);
        if (!events.equals(events.stream().sorted(order).toList())) {
            throw new IllegalArgumentException("公告as-of事件排序无效");
        }
        Set<String> identities = new HashSet<>();
        Set<String> versions = new HashSet<>();
        for (ObservationRecord value : events) {
            if (!symbol.equals(value.symbol())
                    || !identities.add(value.sourceAnnouncementId())
                    || !versions.add(value.observationVersion())
                    || value.securityName() == null
                    || value.securityName().isBlank()
                    || value.securityName().length() > 128
                    || value.title() == null
                    || value.title().isBlank()
                    || value.title().length() > 1024
                    || value.reportedPublishDate() == null
                    || value.reportedPublishDate().isBefore(lookbackStart)
                    || value.reportedPublishDate().isAfter(requestTradeDate)
                    || value.firstObservedAt() == null
                    || value.knownAt() == null
                    || value.reportedPublishDate().isAfter(
                    value.firstObservedAt().atZone(
                            AnnouncementContracts.MARKET_ZONE).toLocalDate())
                    || !value.firstObservedAt().equals(value.knownAt())
                    || value.knownAt().isAfter(knowledgeCutoff)
                    || !AnnouncementContracts.SOURCE_CODE.equals(value.sourceCode())
                    || !AnnouncementContracts.PROVIDER_CONTRACT_VERSION.equals(
                    value.providerContractVersion())
                    || !AnnouncementContracts.ASSURANCE_LEVEL.equals(
                    value.assuranceLevel())
                    || value.formalEligible()
                    || value.pitVerified()
                    || value.revisionRelationshipGuaranteed()
                    || !AnnouncementContracts.PUBLISH_TIME_PRECISION.equals(
                    value.reportedPublishTimePrecision())
                    || value.canonicalContentHash() == null
                    || !value.canonicalContentHash().matches("^[0-9a-f]{64}$")
                    || value.observationVersion() == null
                    || !value.observationVersion().matches("^[0-9a-f]{64}$")) {
                throw new IllegalArgumentException("公告as-of事件字段无效");
            }
            SourceIdentity identity = canonicalService.sourceIdentity(value.sourceUrl());
            if (!identity.sourceAnnouncementId().equals(value.sourceAnnouncementId())
                    || !identity.strength().equals(value.sourceIdentityStrength())
                    || !identity.normalizedUrl().equals(value.normalizedSourceUrl())
                    || !AnnouncementCanonicalService.sha256(value.normalizedSourceUrl())
                    .equals(value.sourceUrlHash())) {
                throw new IllegalArgumentException("公告来源身份无效");
            }
            AnnouncementFact fact = fact(value);
            String expectedVersion = AnnouncementCanonicalService.sha256(String.join(
                    "\n",
                    "ANNOUNCEMENT_OBSERVATION_V1",
                    value.batchVersion(),
                    value.sourceAnnouncementId(),
                    value.canonicalContentHash(),
                    value.firstObservedAt().toString()));
            if (!canonicalService.hashMatches(fact)
                    || !expectedVersion.equals(value.observationVersion())) {
                throw new IllegalArgumentException("公告Hash或观察版本无效");
            }
        }
    }

    private AnnouncementFact fact(ObservationRecord value) {
        return new AnnouncementFact(
                value.symbol(),
                value.securityName(),
                value.title(),
                value.reportedPublishDate(),
                value.sourceUrl(),
                value.normalizedSourceUrl(),
                value.sourceUrlHash(),
                value.sourceAnnouncementId(),
                value.sourceIdentityStrength(),
                value.firstObservedAt(),
                value.canonicalContentHash(),
                value.observationVersion(),
                value.rawPayload());
    }

    private static boolean validSource(CaptureBatchRecord value, String symbol) {
        return symbol.equals(value.symbol())
                && AnnouncementContracts.SOURCE_CODE.equals(value.sourceCode())
                && AnnouncementContracts.PROVIDER_CONTRACT_VERSION.equals(
                value.providerContractVersion())
                && value.requestedStartDate() != null
                && value.requestedEndDate() != null
                && !value.requestedEndDate().isBefore(value.requestedStartDate())
                && value.observedAt() != null
                && value.chunkCount() >= 1
                && value.successfulChunkCount() == value.chunkCount()
                && value.recordCount() >= 0
                && value.appendedCount() >= 0
                && value.appendedCount() <= value.recordCount();
    }

    private ObjectNode base(
            String symbol,
            LocalDate requestTradeDate,
            Instant queriedAt,
            Instant knowledgeCutoff,
            LocalDate lookbackStart
    ) {
        ObjectNode context = objectMapper.createObjectNode();
        context.put("available", false);
        context.put("queriedAt", queriedAt.toString());
        ObjectNode scope = context.putObject("queryScope");
        scope.put("symbol", symbol);
        scope.put("tradeDate", requestTradeDate.toString());
        context.put("producer", AnnouncementContracts.PRODUCER);
        context.put("producerVersion", AnnouncementContracts.PRODUCER_VERSION);
        context.put("contextProfile", AnnouncementContracts.CONTEXT_PROFILE);
        context.put("schemaVersion", AnnouncementContracts.CONTEXT_SCHEMA_VERSION);
        context.put("symbol", symbol);
        context.put("requestTradeDate", requestTradeDate.toString());
        context.put("marketTimezone", AnnouncementContracts.MARKET_TIMEZONE);
        context.put("knowledgeCutoff", knowledgeCutoff.toString());
        context.put("lookbackStartDate", lookbackStart.toString());
        context.put("lookbackDays", AnnouncementContracts.LOOKBACK_DAYS);
        context.put("sourceCode", AnnouncementContracts.SOURCE_CODE);
        context.put("providerContractVersion",
                AnnouncementContracts.PROVIDER_CONTRACT_VERSION);
        context.put("assuranceLevel", AnnouncementContracts.ASSURANCE_LEVEL);
        context.put("formalEligible", false);
        context.put("pitVerified", false);
        context.put("revisionRelationshipGuaranteed", false);
        context.put("reportedPublishTimePrecision",
                AnnouncementContracts.PUBLISH_TIME_PRECISION);
        context.put("completeCapture", false);
        context.putNull("captureBatchVersion");
        context.putNull("captureObservedAt");
        context.putNull("captureAgeHours");
        context.put("eventCount", 0);
        context.putArray("events");
        ArrayNode limitations = context.putArray("limitations");
        AnnouncementContracts.LIMITATIONS.forEach(limitations::add);
        return context;
    }

    private ObjectNode unavailable(
            ObjectNode context,
            String reasonCode,
            String reason
    ) {
        context.put("available", false);
        context.put("reasonCode", reasonCode);
        context.put("reason", reason);
        return context;
    }

    private ObjectNode eventNode(ObservationRecord value) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("sourceAnnouncementId", value.sourceAnnouncementId());
        node.put("sourceIdentityStrength", value.sourceIdentityStrength());
        node.put("symbol", value.symbol());
        node.put("securityName", value.securityName());
        node.put("title", value.title());
        node.put("reportedPublishDate", value.reportedPublishDate().toString());
        node.put("reportedPublishTimePrecision",
                value.reportedPublishTimePrecision());
        node.put("sourceUrl", value.sourceUrl());
        node.put("normalizedSourceUrl", value.normalizedSourceUrl());
        node.put("sourceUrlHash", value.sourceUrlHash());
        node.put("firstObservedAt", value.firstObservedAt().toString());
        node.put("knownAt", value.knownAt().toString());
        node.put("canonicalContentHash", value.canonicalContentHash());
        node.put("observationVersion", value.observationVersion());
        node.put("sourceCode", value.sourceCode());
        node.put("providerContractVersion", value.providerContractVersion());
        node.put("assuranceLevel", value.assuranceLevel());
        node.put("formalEligible", value.formalEligible());
        node.put("pitVerified", value.pitVerified());
        node.put("revisionRelationshipGuaranteed",
                value.revisionRelationshipGuaranteed());
        return node;
    }
}

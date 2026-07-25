package com.stockquant.server.agent.announcement;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockquant.server.agent.announcement.AnnouncementCanonicalService.AnnouncementFact;
import com.stockquant.server.agent.announcement.AnnouncementProviderModels.CaptureRequest;
import com.stockquant.server.agent.announcement.AnnouncementProviderModels.CaptureResult;
import com.stockquant.server.agent.announcement.AnnouncementProviderModels.ProviderError;
import com.stockquant.server.agent.announcement.AnnouncementProviderModels.ProviderRecord;
import com.stockquant.server.agent.announcement.AnnouncementProviderModels.ProviderRequest;
import com.stockquant.server.agent.announcement.AnnouncementProviderModels.ProviderResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class AnnouncementIngestionService {

    private static final Set<String> RAW_FIELDS = Set.of(
            "代码", "简称", "公告标题", "公告时间", "公告链接");

    private final AnnouncementProperties properties;
    private final AnnouncementProviderClient providerClient;
    private final AnnouncementCanonicalService canonicalService;
    private final AnnouncementCaptureTransaction captureTransaction;
    private final Clock clock;

    public AnnouncementIngestionService(
            AnnouncementProperties properties,
            AnnouncementProviderClient providerClient,
            AnnouncementCanonicalService canonicalService,
            AnnouncementCaptureTransaction captureTransaction,
            @Qualifier("agentTemporalClock") Clock clock
    ) {
        this.properties = properties;
        this.providerClient = providerClient;
        this.canonicalService = canonicalService;
        this.captureTransaction = captureTransaction;
        this.clock = clock;
    }

    public CaptureResult capture(CaptureRequest request) {
        validateRequest(request);
        if (!properties.isEnabled()) {
            throw new IllegalStateException("AKShare研究公告摄取入口未启用");
        }
        ProviderResponse response = providerClient.fetch(new ProviderRequest(
                request.symbol(),
                "沪深京",
                request.startDate(),
                request.endDate(),
                "",
                ""));
        Instant observedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String batchVersion = "ANNOUNCEMENT_BATCH_V1:"
                + UUID.randomUUID().toString().toLowerCase();
        List<AnnouncementFact> records = validateResponse(
                request, response, observedAt, batchVersion);
        return captureTransaction.persist(
                batchVersion,
                request.symbol(),
                request.startDate(),
                request.endDate(),
                observedAt,
                response,
                records);
    }

    private List<AnnouncementFact> validateResponse(
            CaptureRequest request,
            ProviderResponse response,
            Instant observedAt,
            String batchVersion
    ) {
        if (response == null
                || !AnnouncementContracts.PROVIDER_CONTRACT_VERSION.equals(
                response.providerContractVersion())
                || !AnnouncementContracts.AKSHARE_VERSION.equals(response.akshareVersion())
                || !request.symbol().equals(response.requestedSymbol())
                || !request.startDate().equals(response.requestedStartDate())
                || !request.endDate().equals(response.requestedEndDate())
                || response.records() == null
                || response.errors() == null) {
            throw new IllegalArgumentException("AKShare公告Provider身份或请求回显无效");
        }
        int expectedChunks = (int) Math.ceil(
                ((double) ChronoUnit.DAYS.between(
                        request.startDate(), request.endDate()) + 1) / 30.0);
        if (response.chunkCount() != expectedChunks
                || response.successfulChunkCount() < 0
                || response.successfulChunkCount() > response.chunkCount()
                || response.errors().size()
                != response.chunkCount() - response.successfulChunkCount()
                || response.complete()
                != (response.successfulChunkCount() == response.chunkCount()
                && response.errors().isEmpty())) {
            throw new IllegalArgumentException("AKShare公告Provider分块计数无效");
        }
        for (ProviderError error : response.errors()) {
            if (error == null || blank(error.code())
                    || error.chunkStartDate() == null
                    || error.chunkEndDate() == null
                    || error.chunkEndDate().isBefore(error.chunkStartDate())
                    || error.chunkStartDate().isBefore(request.startDate())
                    || error.chunkEndDate().isAfter(request.endDate())
                    || error.attempts() < 1 || error.attempts() > 3) {
                throw new IllegalArgumentException("AKShare公告Provider错误元数据无效");
            }
        }

        List<AnnouncementFact> values = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        for (ProviderRecord record : response.records()) {
            if (record == null
                    || !request.symbol().equals(record.symbol())
                    || record.reportedPublishDate() == null
                    || record.reportedPublishDate().isBefore(request.startDate())
                    || record.reportedPublishDate().isAfter(request.endDate())
                    || record.reportedPublishDate().isAfter(
                    observedAt.atZone(AnnouncementContracts.MARKET_ZONE).toLocalDate())
                    || !validRawFields(record)) {
                throw new IllegalArgumentException("AKShare公告Provider记录范围或原始字段无效");
            }
            canonicalService.normalizeUrl(record.sourceUrl());
            AnnouncementFact fact = canonicalService.prepare(
                    record, observedAt, batchVersion);
            if (!identities.add(fact.sourceAnnouncementId())) {
                throw new IllegalArgumentException("AKShare公告Provider来源公告ID重复");
            }
            values.add(fact);
        }
        Comparator<AnnouncementFact> order = Comparator
                .comparing(AnnouncementFact::reportedPublishDate)
                .thenComparing(AnnouncementFact::sourceAnnouncementId)
                .thenComparing(AnnouncementFact::title);
        if (!values.equals(values.stream().sorted(order).toList())) {
            throw new IllegalArgumentException("AKShare公告Provider记录排序无效");
        }
        return List.copyOf(values);
    }

    private static void validateRequest(CaptureRequest request) {
        if (request == null
                || request.symbol() == null
                || !request.symbol().matches("^[0-9]{6}$")
                || request.startDate() == null
                || request.endDate() == null
                || request.endDate().isBefore(request.startDate())) {
            throw new IllegalArgumentException("公告摄取symbol或日期范围无效");
        }
        long days = ChronoUnit.DAYS.between(
                request.startDate(), request.endDate()) + 1;
        if (days < 1 || days > 366) {
            throw new IllegalArgumentException("公告摄取范围必须为1至366个自然日");
        }
    }

    private static boolean validRawFields(ProviderRecord record) {
        JsonNode value = record.rawFields();
        if (value == null || !value.isObject()) {
            return false;
        }
        Set<String> names = new HashSet<>();
        value.fieldNames().forEachRemaining(names::add);
        if (!names.equals(RAW_FIELDS)) {
            return false;
        }
        return textualEquals(value, "代码", record.symbol())
                && textualEquals(value, "简称", record.securityName())
                && textualEquals(value, "公告标题", record.title())
                && record.reportedPublishDate() != null
                && textualEquals(
                value,
                "公告时间",
                record.reportedPublishDate().toString())
                && textualEquals(value, "公告链接", record.sourceUrl());
    }

    private static boolean textualEquals(
            JsonNode value,
            String field,
            String expected
    ) {
        JsonNode item = value.get(field);
        return item != null
                && item.isTextual()
                && expected != null
                && expected.equals(item.textValue());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

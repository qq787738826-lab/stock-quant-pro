package com.stockquant.server.agent.announcement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.announcement.AnnouncementCanonicalService.AnnouncementFact;
import com.stockquant.server.agent.announcement.AnnouncementProviderModels.CaptureResult;
import com.stockquant.server.agent.announcement.AnnouncementProviderModels.ProviderError;
import com.stockquant.server.agent.announcement.AnnouncementProviderModels.ProviderResponse;
import com.stockquant.server.agent.announcement.AnnouncementRepository.BatchInsert;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AnnouncementCaptureTransaction {

    private final AnnouncementRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AnnouncementCaptureTransaction(
            AnnouncementRepository repository,
            ObjectMapper objectMapper,
            @Qualifier("agentTemporalClock") Clock clock
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public CaptureResult persist(
            String batchVersion,
            String symbol,
            LocalDate startDate,
            LocalDate endDate,
            Instant observedAt,
            ProviderResponse response,
            List<AnnouncementFact> records
    ) {
        repository.lockCaptureScope(symbol);
        Map<String, String> latest = repository.latestHashes(symbol);
        List<AnnouncementFact> appended = new ArrayList<>();
        for (AnnouncementFact record : records) {
            if (!record.canonicalContentHash().equals(
                    latest.get(record.sourceAnnouncementId()))) {
                appended.add(record);
                latest = new java.util.HashMap<>(latest);
                latest.put(record.sourceAnnouncementId(), record.canonicalContentHash());
            }
        }
        Instant recordedAt = clock.instant();
        if (recordedAt.isBefore(observedAt)) {
            throw new IllegalStateException("公告recordedAt不得早于observedAt");
        }
        ObjectNode metadata = providerMetadata(response);
        long batchId = repository.insertBatch(new BatchInsert(
                batchVersion,
                symbol,
                startDate,
                endDate,
                observedAt,
                response.complete(),
                response.chunkCount(),
                response.successfulChunkCount(),
                records.size(),
                appended.size(),
                metadata,
                recordedAt));
        appended.forEach(record -> repository.insertObservation(
                batchId, batchVersion, record, recordedAt));
        return new CaptureResult(
                batchVersion,
                AnnouncementContracts.SOURCE_CODE,
                AnnouncementContracts.PROVIDER_CONTRACT_VERSION,
                symbol,
                startDate,
                endDate,
                response.complete(),
                response.chunkCount(),
                response.successfulChunkCount(),
                records.size(),
                appended.size());
    }

    private ObjectNode providerMetadata(ProviderResponse response) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("akshareVersion", response.akshareVersion());
        metadata.put("providerContractVersion", response.providerContractVersion());
        metadata.put("complete", response.complete());
        ArrayNode errors = metadata.putArray("errors");
        for (ProviderError error : response.errors()) {
            ObjectNode node = errors.addObject();
            node.put("code", error.code());
            node.put("chunkStartDate", error.chunkStartDate().toString());
            node.put("chunkEndDate", error.chunkEndDate().toString());
            node.put("attempts", error.attempts());
        }
        return metadata;
    }
}

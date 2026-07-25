package com.stockquant.server.agent.announcement;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.List;

public final class AnnouncementProviderModels {

    private AnnouncementProviderModels() {
    }

    public record ProviderRequest(
            String symbol,
            String market,
            LocalDate startDate,
            LocalDate endDate,
            String keyword,
            String category
    ) {
    }

    public record ProviderRecord(
            String symbol,
            String securityName,
            String title,
            LocalDate reportedPublishDate,
            String sourceUrl,
            JsonNode rawFields
    ) {
    }

    public record ProviderError(
            String code,
            LocalDate chunkStartDate,
            LocalDate chunkEndDate,
            int attempts
    ) {
    }

    public record ProviderResponse(
            String providerContractVersion,
            String akshareVersion,
            String requestedSymbol,
            LocalDate requestedStartDate,
            LocalDate requestedEndDate,
            boolean complete,
            int chunkCount,
            int successfulChunkCount,
            List<ProviderRecord> records,
            List<ProviderError> errors
    ) {
    }

    public record CaptureRequest(
            String symbol,
            LocalDate startDate,
            LocalDate endDate
    ) {
    }

    public record CaptureResult(
            String batchVersion,
            String sourceCode,
            String providerContractVersion,
            String symbol,
            LocalDate requestedStartDate,
            LocalDate requestedEndDate,
            boolean complete,
            int chunkCount,
            int successfulChunkCount,
            int recordCount,
            int appendedCount
    ) {
    }
}

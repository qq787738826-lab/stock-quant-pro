package com.stockquant.server.agent.marketfacts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Tushare reference DTOs that remain outside the four V13 fact contracts.
 *
 * <p>In particular, {@link DividendEvidence} is partial provider evidence and
 * must not be converted to CORPORATE_ACTION_OBSERVATION_V1 without a future
 * stable action identity and complete action semantics.</p>
 */
public final class TushareReferenceDataModels {

    private TushareReferenceDataModels() {
    }

    public record InstrumentIdentity(
            String providerInstrumentId,
            String symbol,
            String exchange,
            String name,
            String market,
            String listStatus,
            LocalDate listDate,
            LocalDate delistDate
    ) {
    }

    public record DividendEvidence(
            String tsCode,
            LocalDate endDate,
            LocalDate announcementDate,
            LocalDate implementationAnnouncementDate,
            String processStatus,
            BigDecimal stockDividend,
            BigDecimal bonusShareRate,
            BigDecimal capitalConversionRate,
            BigDecimal cashDividend,
            BigDecimal cashDividendAfterTax,
            LocalDate recordDate,
            LocalDate exDate,
            LocalDate payDate,
            LocalDate listingDate
    ) {
    }

    public record ReferenceDataResponse<T>(
            String endpoint,
            List<String> responseFields,
            List<T> values,
            int providerCallCount,
            int rateLimitRetryCount,
            boolean v13CorporateActionEligible
    ) {
        public ReferenceDataResponse {
            responseFields = List.copyOf(responseFields);
            values = List.copyOf(values);
            if (endpoint == null || endpoint.isBlank()
                    || providerCallCount <= 0
                    || rateLimitRetryCount < 0
                    || rateLimitRetryCount >= providerCallCount
                    || v13CorporateActionEligible) {
                throw new IllegalArgumentException(
                        "invalid Tushare reference response");
            }
        }
    }
}

package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactResponse;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RunNamespace;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchCommand.SecuritySelection;
import com.stockquant.server.agent.marketfacts.TushareManualBoundedSession.SessionProfile;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, factory-validated boundary between the F1E shared provider
 * session and the dedicated atomic capture entry.
 *
 * <p>The capture service accepts this contract instead of a naked response
 * list so callers cannot detach persistence from the original command,
 * ordering and session budget.</p>
 */
public final class TushareDedicatedResearchCaptureContract {

    private static final Set<FactType> FACT_TYPES = Set.of(
            FactType.RAW_DAILY_BAR,
            FactType.ADJUSTMENT_FACTOR,
            FactType.TRADING_CALENDAR);

    private final LocalDate tradeDate;
    private final List<SecuritySelection> orderedSecurities;
    private final int expectedProviderCallCount;
    private final int expectedRetryCount;
    private final SessionProfile expectedSessionProfile;
    private final boolean automaticRetryAllowed;
    private final Set<FactType> expectedFactTypes;
    private final List<MarketFactResponse> responses;

    private TushareDedicatedResearchCaptureContract(
            LocalDate tradeDate,
            List<SecuritySelection> orderedSecurities,
            int expectedProviderCallCount,
            int expectedRetryCount,
            SessionProfile expectedSessionProfile,
            boolean automaticRetryAllowed,
            Set<FactType> expectedFactTypes,
            List<MarketFactResponse> responses
    ) {
        this.tradeDate = Objects.requireNonNull(
                tradeDate, "tradeDate");
        this.orderedSecurities = List.copyOf(Objects.requireNonNull(
                orderedSecurities, "orderedSecurities"));
        this.expectedProviderCallCount = expectedProviderCallCount;
        this.expectedRetryCount = expectedRetryCount;
        this.expectedSessionProfile = Objects.requireNonNull(
                expectedSessionProfile, "expectedSessionProfile");
        this.automaticRetryAllowed = automaticRetryAllowed;
        this.expectedFactTypes = Set.copyOf(Objects.requireNonNull(
                expectedFactTypes, "expectedFactTypes"));
        this.responses = List.copyOf(Objects.requireNonNull(
                responses, "responses"));
        validateFrozen();
    }

    public static TushareDedicatedResearchCaptureContract validated(
            TushareDedicatedResearchBatchCommand command,
            TushareManualBoundedSession session,
            List<MarketFactResponse> responses
    ) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(session, "session");
        Set<String> commandSymbols = command.securities().stream()
                .map(SecuritySelection::providerInstrumentId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<String> commandExchanges = command.securities().stream()
                .map(SecuritySelection::exchange)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (session.sessionProfile()
                != SessionProfile.F1E_DEDICATED_LOCAL_MANUAL
                || session.maximumBusinessRequests()
                != command.expectedProviderRequests()
                || session.consumedBusinessRequests()
                != command.expectedProviderRequests()
                || !session.allowedSymbols().equals(commandSymbols)
                || !session.allowedExchanges().equals(commandExchanges)
                || !session.allowedStart().equals(command.tradeDate())
                || !session.allowedEnd().equals(command.tradeDate())
                || !session.allowedEndpoints().equals(
                TushareManualBoundedSession.F1E_ALLOWED_ENDPOINTS)
                || session.automaticRetryAllowed()) {
            throw invalid();
        }
        return new TushareDedicatedResearchCaptureContract(
                command.tradeDate(),
                command.securities(),
                command.expectedProviderRequests(),
                0,
                session.sessionProfile(),
                session.automaticRetryAllowed(),
                FACT_TYPES,
                responses);
    }

    void validateFrozen() {
        if (orderedSecurities.isEmpty()
                || orderedSecurities.size()
                > TushareDedicatedResearchBatchCommand.MAXIMUM_SYMBOLS
                || responses.size() != orderedSecurities.size()
                || expectedProviderCallCount
                != orderedSecurities.size()
                * TushareDedicatedResearchBatchCommand
                .REQUESTS_PER_SYMBOL
                || expectedProviderCallCount
                > TushareDedicatedResearchBatchCommand
                .MAXIMUM_PROVIDER_REQUESTS
                || expectedRetryCount != 0
                || expectedSessionProfile
                != SessionProfile.F1E_DEDICATED_LOCAL_MANUAL
                || automaticRetryAllowed
                || !expectedFactTypes.equals(FACT_TYPES)) {
            throw invalid();
        }

        Set<String> identities = new LinkedHashSet<>();
        int providerCalls = 0;
        int retryCount = 0;
        for (int index = 0; index < orderedSecurities.size(); index++) {
            SecuritySelection security = Objects.requireNonNull(
                    orderedSecurities.get(index), "security");
            if (!isMainBoard(security.symbol(), security.exchange())
                    || !identities.add(
                    security.providerInstrumentId())) {
                throw invalid();
            }
            MarketFactResponse response = Objects.requireNonNull(
                    responses.get(index), "response");
            validateResponse(response, security, index);
            providerCalls += metadataInt(
                    response.providerMetadata(), "providerCallCount");
            retryCount += metadataInt(
                    response.providerMetadata(), "rateLimitRetryCount");
        }
        if (providerCalls != expectedProviderCallCount
                || retryCount != expectedRetryCount) {
            throw invalid();
        }
    }

    private void validateResponse(
            MarketFactResponse response,
            SecuritySelection security,
            int index
    ) {
        JsonNode metadata = response.providerMetadata();
        if (!response.complete()
                || !response.errors().isEmpty()
                || response.runNamespace() != RunNamespace.FORMAL
                || !TushareMarketFactProvider.PROVIDER_CODE.equals(
                response.providerCode())
                || !TushareMarketFactProvider.PROVIDER_CODE.equals(
                response.sourceCode())
                || !TushareMarketFactProvider.ADAPTER_VERSION.equals(
                response.adapterVersion())
                || !TushareMarketFactProvider.sourceInstrumentId(
                security.symbol(), security.exchange()).equals(
                response.sourceInstrumentId())
                || !tradeDate.equals(response.requestedStart())
                || !tradeDate.equals(response.requestedEnd())
                || response.rawDailyBars().size() != 1
                || response.adjustmentFactors().size() != 1
                || response.tradingCalendar().size() != 1
                || !response.corporateActions().isEmpty()
                || response.recordCount() != 3
                || !response.capability().supportedFactTypes()
                .equals(expectedFactTypes)
                || metadataInt(metadata, "providerCallCount") != 3
                || metadataInt(metadata, "rateLimitRetryCount") != 0
                || metadataInt(
                metadata, "sessionMaximumBusinessRequests")
                != expectedProviderCallCount
                || metadataInt(
                metadata, "sessionConsumedBusinessRequests")
                != (index + 1) * 3
                || !expectedSessionProfile.name().equals(
                metadataText(metadata, "sessionProfile"))
                || metadataBoolean(
                metadata, "automaticRetryAllowed")
                || !metadataFactTypes(metadata).equals(
                expectedFactTypes)) {
            throw invalid();
        }
    }

    private static Set<FactType> metadataFactTypes(JsonNode metadata) {
        JsonNode value = metadata == null
                ? null : metadata.get("requestedFactTypes");
        if (value == null || !value.isArray()) {
            throw invalid();
        }
        Set<FactType> result = new LinkedHashSet<>();
        value.forEach(item -> {
            if (!item.isTextual()) {
                throw invalid();
            }
            try {
                if (!result.add(FactType.valueOf(item.textValue()))) {
                    throw invalid();
                }
            } catch (IllegalArgumentException error) {
                throw invalid();
            }
        });
        return Set.copyOf(result);
    }

    private static int metadataInt(JsonNode metadata, String field) {
        JsonNode value = metadata == null ? null : metadata.get(field);
        if (value == null || !value.isIntegralNumber()
                || !value.canConvertToInt()) {
            throw invalid();
        }
        return value.intValue();
    }

    private static String metadataText(JsonNode metadata, String field) {
        JsonNode value = metadata == null ? null : metadata.get(field);
        if (value == null || !value.isTextual()
                || value.textValue().isBlank()) {
            throw invalid();
        }
        return value.textValue();
    }

    private static boolean metadataBoolean(
            JsonNode metadata,
            String field
    ) {
        JsonNode value = metadata == null ? null : metadata.get(field);
        if (value == null || !value.isBoolean()) {
            throw invalid();
        }
        return value.booleanValue();
    }

    private static boolean isMainBoard(String symbol, String exchange) {
        return switch (exchange) {
            case "SSE" -> symbol != null
                    && symbol.matches("60[0135][0-9]{3}");
            case "SZSE" -> symbol != null
                    && symbol.matches("00[0123][0-9]{3}");
            default -> false;
        };
    }

    public LocalDate tradeDate() {
        return tradeDate;
    }

    public List<SecuritySelection> orderedSecurities() {
        return orderedSecurities;
    }

    public int expectedProviderCallCount() {
        return expectedProviderCallCount;
    }

    public int expectedRetryCount() {
        return expectedRetryCount;
    }

    public SessionProfile expectedSessionProfile() {
        return expectedSessionProfile;
    }

    public boolean automaticRetryAllowed() {
        return automaticRetryAllowed;
    }

    public Set<FactType> expectedFactTypes() {
        return expectedFactTypes;
    }

    public List<MarketFactResponse> responses() {
        return responses;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "TUSHARE_DEDICATED_RESEARCH_CAPTURE_CONTRACT_INVALID");
    }
}

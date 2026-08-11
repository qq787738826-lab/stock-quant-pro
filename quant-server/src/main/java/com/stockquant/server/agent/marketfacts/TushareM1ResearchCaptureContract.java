package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactResponse;
import com.stockquant.server.agent.marketfacts.TushareDedicatedResearchBatchCommand.SecuritySelection;
import com.stockquant.server.agent.marketfacts.TushareM1ResearchWindowValidator.ValidatedWindow;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable validation boundary between M1 Provider responses and V13. */
final class TushareM1ResearchCaptureContract {
    private static final Set<FactType> FACT_TYPES = Set.of(
            FactType.RAW_DAILY_BAR,
            FactType.ADJUSTMENT_FACTOR,
            FactType.TRADING_CALENDAR);

    private final TushareM1ResearchWindowCommand command;
    private final List<MarketFactResponse> responses;
    private final List<ValidatedWindow> validatedWindows;

    private TushareM1ResearchCaptureContract(
            TushareM1ResearchWindowCommand command,
            List<MarketFactResponse> responses,
            List<ValidatedWindow> validatedWindows
    ) {
        this.command = Objects.requireNonNull(command, "command");
        this.responses = List.copyOf(responses);
        this.validatedWindows = List.copyOf(validatedWindows);
        validateFrozen();
    }

    static TushareM1ResearchCaptureContract validated(
            TushareM1ResearchWindowCommand command,
            TushareManualBoundedSession session,
            List<MarketFactResponse> responses
    ) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(session, "session");
        responses = List.copyOf(Objects.requireNonNull(
                responses, "responses"));
        if (session.sessionProfile()
                != TushareManualBoundedSession.SessionProfile
                .M1_RESEARCH_DATA_MANUAL
                || session.maximumBusinessRequests()
                != command.expectedProviderRequests()
                || session.consumedBusinessRequests()
                != command.expectedProviderRequests()
                || !session.allowedSymbols().equals(
                command.providerInstrumentIds())
                || !session.allowedExchanges().equals(command.exchanges())
                || !session.allowedStart().equals(command.rangeStart())
                || !session.allowedEnd().equals(command.rangeEnd())
                || !session.allowedEndpoints().equals(
                TushareManualBoundedSession.F1E_ALLOWED_ENDPOINTS)
                || session.automaticRetryAllowed()) {
            throw invalid();
        }
        List<ValidatedWindow> windows = new ArrayList<>();
        for (int index = 0; index < responses.size(); index++) {
            MarketFactResponse response = responses.get(index);
            SecuritySelection security = command.securities().get(index);
            ValidatedWindow window = TushareM1ResearchWindowValidator
                    .validate(response, security, command);
            JsonNode metadata = response.providerMetadata();
            if (!response.capability().supportedFactTypes().equals(FACT_TYPES)
                    || metadataInt(metadata,
                    "sessionMaximumBusinessRequests")
                    != command.expectedProviderRequests()
                    || metadataInt(metadata,
                    "sessionConsumedBusinessRequests") != (index + 1) * 3
                    || !"M1_RESEARCH_DATA_MANUAL".equals(
                    metadataText(metadata, "sessionProfile"))
                    || metadataBoolean(metadata, "automaticRetryAllowed")
                    || !metadataFactTypes(metadata).equals(FACT_TYPES)) {
                throw invalid();
            }
            windows.add(window);
        }
        return new TushareM1ResearchCaptureContract(
                command, responses, windows);
    }

    void validateFrozen() {
        if (responses.size() != command.securities().size()
                || validatedWindows.size() != responses.size()
                || responses.isEmpty()
                || command.expectedProviderRequests() > 9) {
            throw invalid();
        }
        int providerCalls = validatedWindows.stream().mapToInt(
                ValidatedWindow::providerCallCount).sum();
        int retries = validatedWindows.stream().mapToInt(
                ValidatedWindow::retryCount).sum();
        if (providerCalls != command.expectedProviderRequests()
                || retries != 0) {
            throw invalid();
        }
    }

    TushareM1ResearchWindowCommand command() {
        return command;
    }

    List<MarketFactResponse> responses() {
        return responses;
    }

    List<ValidatedWindow> validatedWindows() {
        return validatedWindows;
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
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw invalid();
        }
        return value.asText();
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

    private static Set<FactType> metadataFactTypes(JsonNode metadata) {
        JsonNode values = metadata == null
                ? null : metadata.get("requestedFactTypes");
        if (values == null || !values.isArray()) {
            throw invalid();
        }
        Set<FactType> result = new LinkedHashSet<>();
        values.forEach(value -> {
            if (!value.isTextual()) {
                throw invalid();
            }
            try {
                if (!result.add(FactType.valueOf(value.asText()))) {
                    throw invalid();
                }
            } catch (IllegalArgumentException error) {
                throw invalid();
            }
        });
        return Set.copyOf(result);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "TUSHARE_M1_CAPTURE_CONTRACT_INVALID");
    }
}

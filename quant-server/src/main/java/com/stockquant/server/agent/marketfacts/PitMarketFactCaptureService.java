package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.backtest.BacktestCanonicalHashService;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.AssuranceLevel;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.FactType;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactRequest;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.MarketFactResponse;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.ProviderVersion;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RevisionQualification;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.RunNamespace;
import com.stockquant.server.agent.marketfacts.MarketFactProviderModels.UsageQualification;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.BatchIdentity;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.CaptureResult;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.ContentQualification;
import com.stockquant.server.agent.marketfacts.PitMarketFactModels.FactEnvelope;
import com.stockquant.server.agent.temporal.TemporalMarketFoundationService;
import com.stockquant.server.agent.temporal.TemporalModels.DatasetVersion;
import com.stockquant.server.agent.temporal.TemporalModels.RegisterDatasetVersionCommand;
import com.stockquant.server.agent.temporal.TemporalTrustLevel;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;

/**
 * Captures a fully received provider response. External calls happen before
 * this transactional method; Java assigns all local knowledge-time fields.
 */
@Service
public class PitMarketFactCaptureService {

    private final ObjectMapper objectMapper;
    private final PitMarketFactsCanonicalService canonical;
    private final PitMarketFactRepository repository;
    private final TemporalMarketFoundationService temporalFoundation;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    public PitMarketFactCaptureService(
            ObjectMapper objectMapper,
            PitMarketFactsCanonicalService canonical,
            PitMarketFactRepository repository,
            TemporalMarketFoundationService temporalFoundation,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.objectMapper = objectMapper;
        this.canonical = canonical;
        this.repository = repository;
        this.temporalFoundation = temporalFoundation;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(
                transactionManager);
    }

    public CaptureResult fetchAndCapture(
            MarketFactProvider provider,
            MarketFactRequest request
    ) {
        MarketFactResponse response = provider.fetch(request);
        Instant observedAt = BacktestCanonicalHashService.microsecondInstant(
                clock.instant());
        validateResponse(request, provider, response);
        return transactionTemplate.execute(status ->
                captureWithinTransaction(response, observedAt));
    }

    @Transactional
    public CaptureResult capture(MarketFactResponse response, Instant observedAt) {
        return captureWithinTransaction(response, observedAt);
    }

    private CaptureResult captureWithinTransaction(
            MarketFactResponse response,
            Instant observedAt
    ) {
        Instant stableObservedAt =
                BacktestCanonicalHashService.microsecondInstant(observedAt);
        validateResponse(null, null, response);
        Qualification qualification = qualification(response);
        List<TypedFact> facts = sortedFacts(response);
        validateObservationTime(facts, stableObservedAt);

        ContentQualification contentQualification =
                qualification.contentQualification(response);
        ObjectNode responsePayload = responsePayload(
                response, facts, contentQualification);
        String responseHash = canonical.hash(responsePayload);
        String observedText =
                BacktestCanonicalHashService.formatInstant(stableObservedAt);
        String datasetVersion = "LOCAL_PIT_DATASET_V2-"
                + canonical.hash(textNode("responseHash", responseHash,
                "observedAt", observedText));
        String batchVersion = canonical.hash(textNode(
                "contractVersion", PitMarketFactsContracts.MARKET_FACTS_VERSION,
                "datasetVersion", datasetVersion,
                "responseHash", responseHash,
                "observedAt", observedText));

        ObjectNode datasetMetadata = objectMapper.createObjectNode();
        datasetMetadata.put("providerContractVersion",
                PitMarketFactsContracts.PROVIDER_CONTRACT_VERSION);
        datasetMetadata.put("marketFactsContractVersion",
                PitMarketFactsContracts.MARKET_FACTS_VERSION);
        datasetMetadata.put("runNamespace", response.runNamespace().name());
        datasetMetadata.put("sourceInstrumentId", response.sourceInstrumentId());
        datasetMetadata.put("responseComplete", response.complete());
        DatasetVersion dataset = temporalFoundation.registerDatasetVersion(
                new RegisterDatasetVersionCommand(
                        PitMarketFactsContracts.MARKET_FACTS_VERSION,
                        response.sourceCode(),
                        datasetVersion,
                        response.adapterVersion(),
                        response.requestedStart(),
                        response.requestedEnd(),
                        stableObservedAt,
                        responseHash,
                        TemporalTrustLevel.OBSERVED,
                        datasetMetadata));

        BatchIdentity identity = new BatchIdentity(
                batchVersion,
                datasetVersion,
                qualification.providerDatasetVersion(),
                response.runNamespace(),
                response.sourceCode(),
                response.sourceInstrumentId(),
                qualification.revisionQualification(),
                qualification.assuranceLevel(),
                qualification.usageQualification(),
                qualification.formalEligible(),
                response.capability().localPersistenceAllowed(),
                response.capability().historicalReplayAllowed(),
                response.capability().backtestAllowed(),
                response.capability().agentUseAllowed());
        ArrayNode contractArray = objectMapper.createArrayNode();
        response.capability().supportedFactTypes().stream()
                .sorted()
                .forEach(type -> contractArray.add(type.contractVersion()));
        ObjectNode factContracts = objectMapper.createObjectNode();
        factContracts.set("versions", contractArray);
        JsonNode capabilities = objectMapper.valueToTree(response.capability());
        long batchId = repository.insertBatch(
                dataset.id(), identity,
                PitMarketFactsContracts.PROVIDER_CONTRACT_VERSION,
                switch (response.runNamespace()) {
                    case TEST -> "TEST_FIXTURE";
                    case DEMO -> "DEMO_FIXTURE";
                    case FORMAL -> "PROVIDER_CAPTURE";
                },
                response.requestedStart(), response.requestedEnd(),
                stableObservedAt, stableObservedAt, response.complete(),
                response.recordCount(), factContracts, capabilities,
                captureMetadata(response));

        int appended = 0;
        int idempotent = 0;
        List<TypedFact> persistableFacts = response.complete()
                ? facts : List.of();
        for (TypedFact typed : persistableFacts) {
            String naturalKey = MarketFactProviderModels.naturalKey(
                    typed.type(), typed.value());
            String sourceIdentity =
                    MarketFactProviderModels.sourceIdentity(typed.value());
            repository.lockChain(
                    typed.type(), response.sourceCode(),
                    sourceIdentity, naturalKey);
            FactEnvelope tail = repository.findTail(
                    typed.type(), response.sourceCode(),
                    sourceIdentity, naturalKey).orElse(null);
            String contentHash = canonical.contentHash(
                    typed.type(), response.sourceCode(),
                    sourceIdentity, naturalKey, typed.value(),
                    contentQualification);
            ProviderVersion version = MarketFactProviderModels.version(typed.value());
            if (tail != null
                    && tail.canonicalContentHash().equals(contentHash)) {
                idempotent++;
                continue;
            }
            int sequence = tail == null ? 1 : tail.chainSequence() + 1;
            Instant knownAt = knownAt(version, stableObservedAt);
            String observationVersion = canonical.observationVersion(
                    typed.type(), response.sourceCode(),
                    sourceIdentity, naturalKey, sequence,
                    tail == null ? null : tail.observationVersion(),
                    batchVersion, stableObservedAt, knownAt,
                    contentHash, typed.value());
            FactEnvelope inserted = repository.insertObservation(
                    batchId, identity, typed.type(), naturalKey, sequence,
                    tail == null ? null : tail.id(), sourceIdentity, version,
                    stableObservedAt, knownAt, stableObservedAt,
                    contentHash, observationVersion,
                    rawPayload(typed.value()));
            repository.insertTyped(inserted, typed.value());
            appended++;
        }
        return new CaptureResult(
                batchId, batchVersion, dataset.id(), datasetVersion,
                facts.size(), appended, idempotent, response.complete());
    }

    private void validateResponse(
            MarketFactRequest request,
            MarketFactProvider provider,
            MarketFactResponse response
    ) {
        if (response == null) throw new IllegalArgumentException("null response");
        if (request != null) {
            if (!request.runNamespace().equals(response.runNamespace())
                    || !request.sourceCode().equals(response.sourceCode())
                    || !request.sourceInstrumentId()
                    .equals(response.sourceInstrumentId())
                    || !request.rangeStart().equals(response.requestedStart())
                    || !request.rangeEnd().equals(response.requestedEnd())) {
                throw new IllegalArgumentException("provider response identity mismatch");
            }
            if (provider == null
                    || !provider.capability().equals(response.capability())) {
                throw new IllegalArgumentException(
                        "provider capability response mismatch");
            }
        }
        if (!response.capability().localPersistenceAllowed()) {
            throw new IllegalArgumentException(
                    "provider capability does not permit local persistence");
        }
        if (response.recordCount() > 0 && sortedFacts(response).stream()
                .anyMatch(fact -> !response.capability().supportedFactTypes()
                        .contains(fact.type()))) {
            throw new IllegalArgumentException("response contains unsupported fact type");
        }
        List<TypedFact> facts = sortedFacts(response);
        Set<String> keys = new HashSet<>();
        for (TypedFact fact : facts) {
            String key = fact.type().name() + "|"
                    + MarketFactProviderModels.sourceIdentity(fact.value())
                    + "|"
                    + MarketFactProviderModels.naturalKey(
                    fact.type(), fact.value());
            if (!keys.add(key)) {
                throw new IllegalArgumentException(
                        "provider response contains a duplicate natural key");
            }
            if (request != null
                    && !request.factTypes().contains(fact.type())) {
                throw new IllegalArgumentException(
                        "provider returned an unrequested fact type");
            }
            validateProviderVersionCapability(
                    response,
                    MarketFactProviderModels.version(fact.value()));
            validateFactScope(response, request, fact);
        }
    }

    private static void validateProviderVersionCapability(
            MarketFactResponse response,
            ProviderVersion version
    ) {
        if (version.revisionQualification()
                != RevisionQualification.PROVIDER_VERIFIED) {
            return;
        }
        if (!response.capability().revisionIdAvailable()
                || !response.capability().providerPublishedAtAvailable()
                || version.providerSnapshotId() != null
                && !response.capability().snapshotIdAvailable()
                || version.providerUpdatedAt() != null
                && !response.capability().providerUpdatedAtAvailable()) {
            throw new IllegalArgumentException(
                    "verified provider metadata exceeds frozen capability");
        }
    }

    private static void validateFactScope(
            MarketFactResponse response,
            MarketFactRequest request,
            TypedFact fact
    ) {
        if (fact.value()
                instanceof MarketFactProviderModels.RawDailyBar value) {
            validateDate(response, value.tradeDate());
            if (request != null
                    && (!request.symbol().equals(value.symbol())
                    || !request.exchange().equals(value.exchange()))) {
                throw new IllegalArgumentException(
                        "raw daily bar request scope mismatch");
            }
        } else if (fact.value()
                instanceof MarketFactProviderModels.AdjustmentFactor value) {
            validateDate(response, value.factorEffectiveTradeDate());
            if (request != null
                    && !request.symbol().equals(value.symbol())) {
                throw new IllegalArgumentException(
                        "adjustment factor request scope mismatch");
            }
        } else if (fact.value()
                instanceof MarketFactProviderModels.TradingCalendar value) {
            validateDate(response, value.calendarDate());
            if (request != null
                    && !request.exchange().equals(value.exchange())) {
                throw new IllegalArgumentException(
                        "trading calendar request scope mismatch");
            }
        } else if (fact.value()
                instanceof MarketFactProviderModels.CorporateAction value) {
            validateDate(response, value.effectiveTradeDate());
            if (request != null
                    && !request.symbol().equals(value.symbol())) {
                throw new IllegalArgumentException(
                        "corporate action request scope mismatch");
            }
        }
    }

    private static void validateDate(
            MarketFactResponse response,
            java.time.LocalDate date
    ) {
        if (date.isBefore(response.requestedStart())
                || date.isAfter(response.requestedEnd())) {
            throw new IllegalArgumentException(
                    "provider fact falls outside the requested range");
        }
    }

    private Qualification qualification(MarketFactResponse response) {
        List<ProviderVersion> versions = sortedFacts(response).stream()
                .map(fact -> MarketFactProviderModels.version(fact.value()))
                .toList();
        RevisionQualification qualification = versions.isEmpty()
                ? RevisionQualification.SYSTEM_KNOWLEDGE_ONLY
                : versions.get(0).revisionQualification();
        String providerDatasetVersion = versions.isEmpty()
                ? null : versions.get(0).providerDatasetVersion();
        if (versions.stream().anyMatch(version ->
                version.revisionQualification() != qualification
                        || !Objects.equals(version.providerDatasetVersion(),
                        providerDatasetVersion))) {
            throw new IllegalArgumentException(
                    "one capture must use one provider dataset qualification");
        }
        AssuranceLevel assurance = qualification
                == RevisionQualification.PROVIDER_VERIFIED
                ? AssuranceLevel.PROVIDER_PIT_VERIFIED
                : AssuranceLevel.SYSTEM_KNOWLEDGE_PIT;
        UsageQualification usageQualification;
        boolean formalEligible;
        if (response.runNamespace() == RunNamespace.FORMAL) {
            JsonNode licensing = response.capability().licensing();
            JsonNode usageNode = licensing.get("usageQualification");
            JsonNode formalNode = licensing.get("formalEligible");
            if (usageNode == null || !usageNode.isTextual()
                    || formalNode == null || !formalNode.isBoolean()) {
                throw new IllegalArgumentException(
                        "formal provider licensing qualification is incomplete");
            }
            try {
                usageQualification = UsageQualification.valueOf(
                        usageNode.asText());
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException(
                        "formal provider usage qualification is invalid",
                        error);
            }
            formalEligible = formalNode.booleanValue();
            if (usageQualification == UsageQualification.TEST_DEMO_ONLY) {
                throw new IllegalArgumentException(
                        "formal provider cannot use TEST_DEMO_ONLY");
            }
        } else {
            usageQualification = UsageQualification.TEST_DEMO_ONLY;
            formalEligible = false;
        }
        return new Qualification(
                providerDatasetVersion, qualification, assurance,
                usageQualification, formalEligible);
    }

    private List<TypedFact> sortedFacts(MarketFactResponse response) {
        List<TypedFact> result = new ArrayList<>();
        response.rawDailyBars().forEach(value ->
                result.add(new TypedFact(FactType.RAW_DAILY_BAR, value)));
        response.adjustmentFactors().forEach(value ->
                result.add(new TypedFact(FactType.ADJUSTMENT_FACTOR, value)));
        response.tradingCalendar().forEach(value ->
                result.add(new TypedFact(FactType.TRADING_CALENDAR, value)));
        response.corporateActions().forEach(value ->
                result.add(new TypedFact(FactType.CORPORATE_ACTION, value)));
        result.sort(Comparator
                .comparing(TypedFact::type)
                .thenComparing(value -> MarketFactProviderModels.naturalKey(
                        value.type(), value.value())));
        return List.copyOf(result);
    }

    private ObjectNode responsePayload(
            MarketFactResponse response,
            List<TypedFact> facts,
            ContentQualification qualification
    ) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("canonicalContractVersion",
                PitMarketFactsContracts.MARKET_FACTS_CANONICAL_VERSION);
        result.put("providerContractVersion", response.providerContractVersion());
        result.put("providerCode", response.providerCode());
        result.put("adapterVersion", response.adapterVersion());
        result.put("sourceCode", response.sourceCode());
        result.put("sourceInstrumentId", response.sourceInstrumentId());
        result.put("requestedStart", response.requestedStart().toString());
        result.put("requestedEnd", response.requestedEnd().toString());
        result.put("complete", response.complete());
        result.set("providerCapability",
                objectMapper.valueToTree(response.capability()));
        result.set("providerMetadata", response.providerMetadata().deepCopy());
        result.set("errors", objectMapper.valueToTree(response.errors()));
        ArrayNode content = result.putArray("facts");
        facts.forEach(fact -> content.add(canonical.contentPayload(
                fact.type(), response.sourceCode(),
                MarketFactProviderModels.sourceIdentity(fact.value()),
                MarketFactProviderModels.naturalKey(
                        fact.type(), fact.value()),
                fact.value(), qualification)));
        return result;
    }

    private ObjectNode captureMetadata(MarketFactResponse response) {
        ObjectNode result = objectMapper.createObjectNode();
        result.set("providerMetadata", response.providerMetadata().deepCopy());
        result.set("errors", objectMapper.valueToTree(response.errors()));
        result.put("adapterVersion", response.adapterVersion());
        result.put("responseComplete", response.complete());
        return result;
    }

    private static void validateObservationTime(
            List<TypedFact> facts,
            Instant observedAt
    ) {
        for (TypedFact fact : facts) {
            ProviderVersion version =
                    MarketFactProviderModels.version(fact.value());
            Instant knownAt = knownAt(version, observedAt);
            if (version.revisionQualification()
                    == RevisionQualification.PROVIDER_VERIFIED) {
                if (version.providerPublishedAt().isAfter(observedAt)
                        || version.providerUpdatedAt() != null
                        && version.providerUpdatedAt().isAfter(observedAt)) {
                    throw new IllegalArgumentException(
                            "verified provider time exceeds first observation");
                }
            }
            if (!(fact.value()
                    instanceof MarketFactProviderModels.RawDailyBar bar)) {
                continue;
            }
            Instant earliestKnownAt = bar.tradeDate()
                    .atTime(LocalTime.of(15, 0))
                    .atZone(PitMarketFactsContracts.MARKET_ZONE)
                    .toInstant();
            if (observedAt.isBefore(earliestKnownAt)
                    || knownAt.isBefore(earliestKnownAt)) {
                throw new IllegalArgumentException(
                        "complete daily bar cannot be observed before "
                                + "15:00 Asia/Shanghai on its trade date");
            }
        }
    }

    private JsonNode rawPayload(Object fact) {
        if (fact instanceof MarketFactProviderModels.RawDailyBar value) {
            return value.rawFields();
        }
        if (fact instanceof MarketFactProviderModels.AdjustmentFactor value) {
            return value.rawFields();
        }
        if (fact instanceof MarketFactProviderModels.TradingCalendar value) {
            return value.rawFields();
        }
        if (fact instanceof MarketFactProviderModels.CorporateAction value) {
            return value.rawFields();
        }
        throw new IllegalArgumentException("unsupported fact");
    }

    private ObjectNode textNode(String... values) {
        ObjectNode result = objectMapper.createObjectNode();
        for (int index = 0; index < values.length; index += 2) {
            result.put(values[index], values[index + 1]);
        }
        return result;
    }

    private record TypedFact(FactType type, Object value) {
    }

    private record Qualification(
            String providerDatasetVersion,
            RevisionQualification revisionQualification,
            AssuranceLevel assuranceLevel,
            UsageQualification usageQualification,
            boolean formalEligible
    ) {
        private ContentQualification contentQualification(
                MarketFactResponse response
        ) {
            return new ContentQualification(
                    assuranceLevel,
                    usageQualification,
                    formalEligible,
                    response.capability().localPersistenceAllowed(),
                    response.capability().historicalReplayAllowed(),
                    response.capability().backtestAllowed(),
                    response.capability().agentUseAllowed());
        }
    }

    private static Instant knownAt(
            ProviderVersion version,
            Instant firstObservedAt
    ) {
        return version.revisionQualification()
                == RevisionQualification.PROVIDER_VERIFIED
                ? version.providerPublishedAt()
                : firstObservedAt;
    }
}

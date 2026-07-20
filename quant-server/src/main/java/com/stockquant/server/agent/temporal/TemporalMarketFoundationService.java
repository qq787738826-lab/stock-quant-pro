package com.stockquant.server.agent.temporal;

import com.stockquant.server.agent.temporal.TemporalModels.AppendSecurityStatusEventCommand;
import com.stockquant.server.agent.temporal.TemporalModels.AppendTradingCalendarRevisionCommand;
import com.stockquant.server.agent.temporal.TemporalModels.CorrectSecurityStatusVersionCommand;
import com.stockquant.server.agent.temporal.TemporalModels.CorrectTradingCalendarRevisionCommand;
import com.stockquant.server.agent.temporal.TemporalModels.DatasetVersion;
import com.stockquant.server.agent.temporal.TemporalModels.PublishSecurityStatusVersionCommand;
import com.stockquant.server.agent.temporal.TemporalModels.RegisterDatasetVersionCommand;
import com.stockquant.server.agent.temporal.TemporalModels.SecurityStatusAsOf;
import com.stockquant.server.agent.temporal.TemporalModels.SecurityStatusEvent;
import com.stockquant.server.agent.temporal.TemporalModels.SecurityStatusVersion;
import com.stockquant.server.agent.temporal.TemporalModels.TradingCalendarAsOf;
import com.stockquant.server.agent.temporal.TemporalModels.TradingCalendarRevision;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Transactional write and as-of query boundary for the stage 2D-2A foundation. */
@Service
public class TemporalMarketFoundationService {

    private final MarketDataDatasetVersionRepository datasetVersions;
    private final SecurityStatusEventRepository securityEvents;
    private final SecurityStatusHistoryRepository securityHistory;
    private final TradingCalendarRevisionRepository calendars;
    private final SecurityStatusStateHasher stateHasher;
    private final Clock clock;

    public TemporalMarketFoundationService(
            MarketDataDatasetVersionRepository datasetVersions,
            SecurityStatusEventRepository securityEvents,
            SecurityStatusHistoryRepository securityHistory,
            TradingCalendarRevisionRepository calendars,
            SecurityStatusStateHasher stateHasher,
            @Qualifier(TemporalClockConfiguration.CLOCK_BEAN) Clock clock
    ) {
        this.datasetVersions = datasetVersions;
        this.securityEvents = securityEvents;
        this.securityHistory = securityHistory;
        this.calendars = calendars;
        this.stateHasher = stateHasher;
        this.clock = clock;
    }

    @Transactional
    public DatasetVersion registerDatasetVersion(RegisterDatasetVersionCommand command) {
        TemporalValidation.required(command, "command");
        Instant recordedAt = recordedAt();
        TemporalValidation.notBefore(recordedAt, command.fetchedAt(), "recordedAt", "fetchedAt");
        DatasetVersion result = datasetVersions.insertIfAbsent(command, recordedAt)
                .orElseGet(() -> datasetVersions.findByIdempotencyKey(command)
                        .orElseThrow(() -> conflict("dataset idempotency lookup failed")));
        verifyDataset(command, result);
        return result;
    }

    @Transactional
    public SecurityStatusEvent appendSecurityStatusEvent(
            AppendSecurityStatusEventCommand command
    ) {
        TemporalValidation.required(command, "command");
        DatasetVersion dataset = requireDataset(command.datasetVersionId());
        verifyDatasetProvenance(
                dataset, command.source(), command.sourceVersion(), command.trustLevel());
        verifySupersededEvent(command);
        Instant recordedAt = recordedAt();
        TemporalValidation.notBefore(recordedAt, command.knownAt(), "recordedAt", "knownAt");
        SecurityStatusEvent result = securityEvents.insertIfAbsent(command, recordedAt)
                .orElseGet(() -> securityEvents.findByIdempotencyKey(command)
                        .orElseThrow(() -> conflict("security event idempotency lookup failed")));
        verifyEvent(command, result);
        return result;
    }

    @Transactional
    public SecurityStatusVersion publishSecurityStatusVersion(
            PublishSecurityStatusVersionCommand command
    ) {
        TemporalValidation.required(command, "command");
        verifyStatusProvenance(command, null);
        String statusHash = stateHasher.hash(command);
        Instant recordedAt = recordedAt();
        TemporalValidation.notBefore(recordedAt, command.knownFrom(), "recordedAt", "knownFrom");
        SecurityStatusVersion result = securityHistory
                .insertIfAbsent(command, statusHash, recordedAt)
                .orElseGet(() -> securityHistory.findByIdempotencyKey(command)
                        .orElseThrow(() -> conflict("security status idempotency lookup failed")));
        verifyStatus(command, statusHash, result);
        return result;
    }

    @Transactional
    public SecurityStatusVersion correctSecurityStatusVersion(
            CorrectSecurityStatusVersionCommand command
    ) {
        TemporalValidation.required(command, "command");
        PublishSecurityStatusVersionCommand replacement = command.replacement();
        SecurityStatusVersion superseded = securityHistory
                .findByIdForUpdate(command.supersededVersionId())
                .orElseThrow(() -> conflict("superseded security status does not exist"));
        verifyStatusProvenance(replacement, superseded);
        String statusHash = stateHasher.hash(replacement);
        Optional<SecurityStatusVersion> existingReplacement =
                securityHistory.findByIdempotencyKey(replacement);
        if (!superseded.symbol().equals(replacement.symbol())) {
            throw conflict("security correction cannot change symbol identity");
        }
        if (!replacement.knownFrom().isAfter(superseded.knownFrom())) {
            throw conflict("security correction knowledge time must advance");
        }
        if (superseded.knownTo() != null) {
            return verifiedSecurityCorrectionRetry(
                    superseded, replacement, statusHash, existingReplacement);
        }
        if (securityHistory.closeKnowledgeInterval(
                superseded.id(), replacement.knownFrom()) != 1) {
            throw conflict("security correction could not close exactly one knowledge interval");
        }
        Instant recordedAt = recordedAt();
        TemporalValidation.notBefore(
                recordedAt, replacement.knownFrom(), "recordedAt", "knownFrom");
        SecurityStatusVersion result = securityHistory
                .insertIfAbsent(replacement, statusHash, recordedAt)
                .or(() -> securityHistory.findByIdempotencyKey(replacement))
                .orElseThrow(() -> conflict("security correction replacement was not inserted"));
        verifyStatus(replacement, statusHash, result);
        return result;
    }

    @Transactional
    public TradingCalendarRevision appendTradingCalendarRevision(
            AppendTradingCalendarRevisionCommand command
    ) {
        TemporalValidation.required(command, "command");
        DatasetVersion dataset = requireDataset(command.datasetVersionId());
        verifyDatasetProvenance(
                dataset, command.source(), command.sourceVersion(), command.trustLevel());
        Instant recordedAt = recordedAt();
        TemporalValidation.notBefore(recordedAt, command.knownFrom(), "recordedAt", "knownFrom");
        TradingCalendarRevision result = calendars.insertIfAbsent(command, recordedAt)
                .orElseGet(() -> calendars.findByIdempotencyKey(command)
                        .orElseThrow(() -> conflict("calendar idempotency lookup failed")));
        verifyCalendar(command, result);
        return result;
    }

    @Transactional
    public TradingCalendarRevision correctTradingCalendarRevision(
            CorrectTradingCalendarRevisionCommand command
    ) {
        TemporalValidation.required(command, "command");
        AppendTradingCalendarRevisionCommand replacement = command.replacement();
        TradingCalendarRevision superseded = calendars
                .findByIdForUpdate(command.supersededRevisionId())
                .orElseThrow(() -> conflict("superseded calendar revision does not exist"));
        DatasetVersion dataset = requireDataset(replacement.datasetVersionId());
        verifyDatasetProvenance(
                dataset, replacement.source(), replacement.sourceVersion(),
                replacement.trustLevel());
        Optional<TradingCalendarRevision> existingReplacement =
                calendars.findByIdempotencyKey(replacement);
        if (superseded.exchange() != replacement.exchange()
                || !superseded.tradeDate().equals(replacement.tradeDate())) {
            throw conflict("calendar correction cannot change exchange or trade date identity");
        }
        if (!replacement.knownFrom().isAfter(superseded.knownFrom())) {
            throw conflict("calendar correction knowledge time must advance");
        }
        if (superseded.knownTo() != null) {
            return verifiedCalendarCorrectionRetry(
                    superseded, replacement, existingReplacement);
        }
        if (calendars.closeKnowledgeInterval(
                superseded.id(), replacement.knownFrom()) != 1) {
            throw conflict("calendar correction could not close exactly one knowledge interval");
        }
        Instant recordedAt = recordedAt();
        TemporalValidation.notBefore(
                recordedAt, replacement.knownFrom(), "recordedAt", "knownFrom");
        TradingCalendarRevision result = calendars
                .insertIfAbsent(replacement, recordedAt)
                .or(() -> calendars.findByIdempotencyKey(replacement))
                .orElseThrow(() -> conflict("calendar correction replacement was not inserted"));
        verifyCalendar(replacement, result);
        return result;
    }

    @Transactional(readOnly = true)
    public Optional<SecurityStatusAsOf> findSecurityStatusAsOf(
            String symbol,
            LocalDate validDate,
            Instant knowledgeCutoff
    ) {
        List<SecurityStatusVersion> candidates = securityHistory.findAsOfCandidates(
                symbol, validDate, knowledgeCutoff);
        if (candidates.size() > 1) {
            throw new TemporalDataAmbiguityException(
                    "multiple security status versions matched one bitemporal as-of query");
        }
        return candidates.stream().findFirst().map(value -> new SecurityStatusAsOf(
                value,
                validDate,
                TemporalValidation.instant(knowledgeCutoff, "knowledgeCutoff"),
                assessSecurityLineage(value)
        ));
    }

    @Transactional(readOnly = true)
    public Optional<TradingCalendarAsOf> findTradingCalendarAsOf(
            MarketExchange exchange,
            LocalDate tradeDate,
            Instant knowledgeCutoff
    ) {
        List<TradingCalendarRevision> candidates = calendars.findAsOfCandidates(
                exchange, tradeDate, knowledgeCutoff);
        if (candidates.size() > 1) {
            throw new TemporalDataAmbiguityException(
                    "multiple calendar revisions matched one knowledge-time as-of query");
        }
        return candidates.stream().findFirst().map(value -> new TradingCalendarAsOf(
                value,
                TemporalValidation.instant(knowledgeCutoff, "knowledgeCutoff"),
                assessCalendarLineage(value)
        ));
    }

    @Transactional(readOnly = true)
    public Optional<LocalDate> findPreviousOpenDateAsOf(
            MarketExchange exchange,
            LocalDate tradeDate,
            Instant knowledgeCutoff
    ) {
        return calendars.findPreviousOpenDateAsOf(exchange, tradeDate, knowledgeCutoff);
    }

    @Transactional(readOnly = true)
    public Optional<LocalDate> findNextOpenDateAsOf(
            MarketExchange exchange,
            LocalDate tradeDate,
            Instant knowledgeCutoff
    ) {
        return calendars.findNextOpenDateAsOf(exchange, tradeDate, knowledgeCutoff);
    }

    private Instant recordedAt() {
        return TemporalValidation.instant(clock.instant(), "recordedAt");
    }

    private DatasetVersion requireDataset(long id) {
        return datasetVersions.findById(id)
                .orElseThrow(() -> conflict("dataset version does not exist"));
    }

    private SecurityStatusEvent requireSecurityEvent(long id) {
        return securityEvents.findById(id)
                .orElseThrow(() -> conflict("security status event does not exist"));
    }

    private void verifySupersededEvent(AppendSecurityStatusEventCommand command) {
        if (command.supersedesEventId() == null) {
            return;
        }
        SecurityStatusEvent superseded = requireSecurityEvent(command.supersedesEventId());
        if (!superseded.symbol().equals(command.symbol())
                || !superseded.source().equals(command.source())) {
            throw conflict("a superseding event must preserve symbol and source identity");
        }
        if (!command.knownAt().isAfter(superseded.knownAt())) {
            throw conflict("a superseding event must advance knowledge time");
        }
    }

    private void verifyStatusProvenance(
            PublishSecurityStatusVersionCommand command,
            SecurityStatusVersion supersededVersion
    ) {
        DatasetVersion dataset = requireDataset(command.datasetVersionId());
        SecurityStatusEvent event = requireSecurityEvent(command.sourceEventId());
        verifyDatasetProvenance(
                dataset, command.source(), command.sourceVersion(), command.trustLevel());
        verifyStatusLineage(
                command.symbol(), command.validFrom(), command.validTo(), command.knownFrom(),
                command.datasetVersionId(), command.source(), command.sourceVersion(),
                command.trustLevel(), event, dataset);
        if (supersededVersion != null
                && !Objects.equals(event.supersedesEventId(), supersededVersion.sourceEventId())) {
            throw conflict("a correction status event must supersede the prior source event");
        }
    }

    private static void verifyStatusLineage(
            String symbol,
            LocalDate validFrom,
            LocalDate validTo,
            Instant knownFrom,
            long datasetVersionId,
            String source,
            String sourceVersion,
            TemporalTrustLevel trustLevel,
            SecurityStatusEvent event,
            DatasetVersion dataset
    ) {
        if (event.datasetVersionId() != datasetVersionId
                || dataset.id() != datasetVersionId
                || !event.symbol().equals(symbol)
                || !event.source().equals(source)
                || !event.sourceVersion().equals(sourceVersion)) {
            throw conflict("security status projection provenance is inconsistent");
        }
        if (validFrom.isBefore(event.effectiveFrom())
                || (event.effectiveTo() != null
                && (validTo == null || validTo.isAfter(event.effectiveTo())))) {
            throw conflict("security status valid interval exceeds its source event interval");
        }
        if (knownFrom.isBefore(event.knownAt())) {
            throw conflict("security status knowledge time precedes its source event");
        }
        if (!event.trustLevel().permitsDerived(trustLevel)
                || !dataset.trustLevel().permitsDerived(trustLevel)) {
            throw conflict("security status trust cannot exceed its provenance chain");
        }
    }

    private static void verifyDatasetProvenance(
            DatasetVersion dataset,
            String source,
            String sourceVersion,
            TemporalTrustLevel derivedTrust
    ) {
        if (!dataset.source().equals(source) || !dataset.sourceVersion().equals(sourceVersion)) {
            throw conflict("fact source does not match its dataset version");
        }
        if (!dataset.trustLevel().permitsDerived(derivedTrust)) {
            throw conflict("fact trust cannot exceed its dataset version");
        }
    }

    private TemporalModels.TemporalTrustAssessment assessSecurityLineage(
            SecurityStatusVersion value
    ) {
        DatasetVersion dataset = requireDataset(value.datasetVersionId());
        SecurityStatusEvent event = requireSecurityEvent(value.sourceEventId());
        verifyStatusLineage(
                value.symbol(), value.validFrom(), value.validTo(), value.knownFrom(),
                value.datasetVersionId(), value.source(), value.sourceVersion(),
                value.trustLevel(), event, dataset);
        TemporalTrustLevel conservative = TemporalTrustLevel.mostConservative(
                dataset.trustLevel(), event.trustLevel(), value.trustLevel());
        return TemporalTrustPolicy.assess(
                conservative, value.knownFrom(), value.source(), value.sourceVersion(), true);
    }

    private TemporalModels.TemporalTrustAssessment assessCalendarLineage(
            TradingCalendarRevision value
    ) {
        DatasetVersion dataset = requireDataset(value.datasetVersionId());
        verifyDatasetProvenance(
                dataset, value.source(), value.sourceVersion(), value.trustLevel());
        TemporalTrustLevel conservative = TemporalTrustLevel.mostConservative(
                dataset.trustLevel(), value.trustLevel());
        return TemporalTrustPolicy.assess(
                conservative, value.knownFrom(), value.source(), value.sourceVersion(), true);
    }

    private static SecurityStatusVersion verifiedSecurityCorrectionRetry(
            SecurityStatusVersion superseded,
            PublishSecurityStatusVersionCommand replacement,
            String statusHash,
            Optional<SecurityStatusVersion> existingReplacement
    ) {
        if (!Objects.equals(superseded.knownTo(), replacement.knownFrom())) {
            throw conflict("security correction retry does not match the closed knowledge interval");
        }
        SecurityStatusVersion existing = existingReplacement.orElseThrow(
                () -> conflict("closed security correction has no matching replacement"));
        verifyStatus(replacement, statusHash, existing);
        return existing;
    }

    private static TradingCalendarRevision verifiedCalendarCorrectionRetry(
            TradingCalendarRevision superseded,
            AppendTradingCalendarRevisionCommand replacement,
            Optional<TradingCalendarRevision> existingReplacement
    ) {
        if (!Objects.equals(superseded.knownTo(), replacement.knownFrom())) {
            throw conflict("calendar correction retry does not match the closed knowledge interval");
        }
        TradingCalendarRevision existing = existingReplacement.orElseThrow(
                () -> conflict("closed calendar correction has no matching replacement"));
        verifyCalendar(replacement, existing);
        return existing;
    }

    private static void verifyDataset(
            RegisterDatasetVersionCommand command,
            DatasetVersion value
    ) {
        if (!command.datasetType().equals(value.datasetType())
                || !command.source().equals(value.source())
                || !command.sourceVersion().equals(value.sourceVersion())
                || !command.connectorVersion().equals(value.connectorVersion())
                || !command.rangeStart().equals(value.rangeStart())
                || !command.rangeEnd().equals(value.rangeEnd())
                || !command.fetchedAt().equals(value.fetchedAt())
                || !command.payloadHash().equals(value.payloadHash())
                || command.trustLevel() != value.trustLevel()
                || !TemporalJsonSemantics.same(command.metadata(), value.metadata())) {
            throw conflict("dataset idempotency key resolved to different immutable content");
        }
    }

    private static void verifyEvent(
            AppendSecurityStatusEventCommand command,
            SecurityStatusEvent value
    ) {
        if (command.datasetVersionId() != value.datasetVersionId()
                || !command.symbol().equals(value.symbol())
                || command.eventType() != value.eventType()
                || !command.effectiveFrom().equals(value.effectiveFrom())
                || !Objects.equals(command.effectiveTo(), value.effectiveTo())
                || !Objects.equals(command.publishedAt(), value.publishedAt())
                || !command.knownAt().equals(value.knownAt())
                || !command.source().equals(value.source())
                || !command.sourceVersion().equals(value.sourceVersion())
                || !command.sourceRecordId().equals(value.sourceRecordId())
                || !command.sourceRevision().equals(value.sourceRevision())
                || command.trustLevel() != value.trustLevel()
                || !TemporalJsonSemantics.same(command.payload(), value.payload())
                || !command.payloadHash().equals(value.payloadHash())
                || !Objects.equals(command.supersedesEventId(), value.supersedesEventId())) {
            throw conflict("security event idempotency key resolved to different immutable content");
        }
    }

    private static void verifyStatus(
            PublishSecurityStatusVersionCommand command,
            String statusHash,
            SecurityStatusVersion value
    ) {
        if (!command.symbol().equals(value.symbol())
                || command.exchange() != value.exchange()
                || !command.board().equals(value.board())
                || command.listed() != value.listed()
                || command.active() != value.active()
                || command.st() != value.st()
                || !command.validFrom().equals(value.validFrom())
                || !Objects.equals(command.validTo(), value.validTo())
                || !command.knownFrom().equals(value.knownFrom())
                || !Objects.equals(command.knownTo(), value.knownTo())
                || command.sourceEventId() != value.sourceEventId()
                || command.datasetVersionId() != value.datasetVersionId()
                || !command.source().equals(value.source())
                || !command.sourceVersion().equals(value.sourceVersion())
                || command.trustLevel() != value.trustLevel()
                || !statusHash.equals(value.statusHash())) {
            throw conflict("security status idempotency key resolved to different immutable content");
        }
    }

    private static void verifyCalendar(
            AppendTradingCalendarRevisionCommand command,
            TradingCalendarRevision value
    ) {
        if (command.datasetVersionId() != value.datasetVersionId()
                || command.exchange() != value.exchange()
                || !command.tradeDate().equals(value.tradeDate())
                || command.open() != value.open()
                || command.sessionType() != value.sessionType()
                || !Objects.equals(command.sessionOpenAt(), value.sessionOpenAt())
                || !Objects.equals(command.sessionCloseAt(), value.sessionCloseAt())
                || !Objects.equals(command.previousOpenDate(), value.previousOpenDate())
                || !Objects.equals(command.nextOpenDate(), value.nextOpenDate())
                || !command.knownFrom().equals(value.knownFrom())
                || !Objects.equals(command.knownTo(), value.knownTo())
                || !command.source().equals(value.source())
                || !command.sourceVersion().equals(value.sourceVersion())
                || !command.sourceRecordId().equals(value.sourceRecordId())
                || !command.sourceRevision().equals(value.sourceRevision())
                || command.trustLevel() != value.trustLevel()
                || !command.payloadHash().equals(value.payloadHash())) {
            throw conflict("calendar idempotency key resolved to different immutable content");
        }
    }

    private static TemporalDataConflictException conflict(String message) {
        return new TemporalDataConflictException(message);
    }
}

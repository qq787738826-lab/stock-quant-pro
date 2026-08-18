package com.stockquant.server.researchselection;

import com.stockquant.core.research.StrategyResearchModels.Security;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Stable contracts for the stock_basic-derived full main-board universe. */
public final class ResearchUniverseMainboard {
    public static final String VERSION = "RESEARCH_UNIVERSE_MAINBOARD_V1";
    public static final String SOURCE = "TUSHARE_STOCK_BASIC";
    public static final int BASIC_MINIMUM_SESSIONS = 20;
    public static final int STABILITY_MINIMUM_SESSIONS = 60;
    public static final int HISTORICAL_LIMIT = 200;
    public static final int STRATEGY_LIMIT = 30;
    public static final int AGENT_LIMIT = 10;
    public static final int MINIMUM_PLAUSIBLE_MEMBER_COUNT = 1_000;
    public static final BigDecimal MINIMUM_AVERAGE_TRADED_AMOUNT =
            new BigDecimal("10000000");

    private ResearchUniverseMainboard() {
    }

    public enum EligibilityStatus { ELIGIBLE, EXCLUDED }

    public enum ExclusionReason {
        ST_SECURITY,
        SUSPENDED_OR_NO_TRADE,
        LISTING_HISTORY_INSUFFICIENT,
        TWENTY_SESSION_HISTORY_INSUFFICIENT,
        DAILY_FACT_MISSING,
        ADJUSTMENT_FACTOR_MISSING,
        TRADE_CALENDAR_INCOMPLETE,
        DATA_QUALITY_FAILED,
        EXTREMELY_LOW_LIQUIDITY,
        PRICE_OR_VOLUME_ANOMALY,
        FUTURE_DATA_GUARD_FAILED
    }

    public record Member(
            String tsCode,
            String symbol,
            String exchange,
            String name,
            String industry,
            String market,
            String listStatus,
            LocalDate listDate,
            LocalDate delistDate,
            Instant snapshotObservedAt,
            String source,
            String contentHash,
            boolean stSecurity
    ) {
        public Security security() {
            return new Security(symbol, exchange);
        }
    }

    public record Snapshot(
            long databaseId,
            String snapshotId,
            String universeVersion,
            int memberCount,
            int sseCount,
            int szseCount,
            int stCount,
            Instant observedAt,
            Instant lastVerifiedAt,
            LocalDate effectiveDate,
            String source,
            String sourceFingerprint,
            String memberFingerprint,
            String gitCommit
    ) {
    }

    public record SnapshotBundle(
            Snapshot snapshot,
            List<Member> members
    ) {
        public SnapshotBundle {
            members = List.copyOf(members);
            if (snapshot == null || members.size() != snapshot.memberCount()) {
                throw new IllegalArgumentException(
                        "MAINBOARD_UNIVERSE_SNAPSHOT_INVALID");
            }
        }
    }

    public record MemberEvaluation(
            Member member,
            EligibilityStatus status,
            List<ExclusionReason> exclusionReasons,
            int availableSessions,
            int missingDaily,
            int missingAdjustmentFactors,
            BigDecimal averageTradedAmount,
            Integer basicRank,
            BigDecimal basicScore,
            Integer historicalRank,
            BigDecimal stabilityScore,
            String historicalGrade,
            Integer strategyRank,
            boolean agentSelected,
            boolean finalCandidate
    ) {
        public MemberEvaluation {
            exclusionReasons = List.copyOf(exclusionReasons);
        }
    }

    public record Funnel(
            String universeVersion,
            String snapshotId,
            int memberCount,
            int sseCount,
            int szseCount,
            int stCount,
            int eligibleCount,
            int excludedCount,
            int suspendedCount,
            int insufficientHistoryCount,
            int basicScannedCount,
            int historicalScoredCount,
            int strategyComparedCount,
            int agentResearchedCount,
            int candidateCount,
            Map<String, Integer> exclusionReasonCounts
    ) {
        public Funnel {
            exclusionReasonCounts = Map.copyOf(exclusionReasonCounts);
        }
    }

    public record BackfillPlan(
            String universeVersion,
            String currentSnapshotId,
            int currentMemberCount,
            int existingSecurityCount,
            LocalDate anchorTradeDate,
            LocalDate rangeStart,
            LocalDate rangeEnd,
            List<LocalDate> requiredTradeDates,
            List<LocalDate> missingTradeDates,
            int stockBasicRequests,
            int dailyRequests,
            int adjustmentFactorRequests,
            int tradeCalendarRequests,
            int networkRecoveryRequests,
            int totalRequests,
            int ledgerUsed,
            int ledgerLimit,
            int scheduledReserve,
            boolean executableWithinBudget
    ) {
        public BackfillPlan {
            requiredTradeDates = List.copyOf(requiredTradeDates);
            missingTradeDates = List.copyOf(missingTradeDates);
        }
    }

    public record MemberPage(
            long runId,
            int page,
            int size,
            long total,
            List<MemberEvaluation> members
    ) {
        public MemberPage {
            members = List.copyOf(members);
        }
    }
}

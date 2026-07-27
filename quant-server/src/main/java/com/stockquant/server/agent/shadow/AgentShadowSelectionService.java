package com.stockquant.server.agent.shadow;

import com.stockquant.server.agent.shadow.AgentShadowModels.SelectionEntry;
import com.stockquant.server.agent.shadow.AgentShadowModels.SelectionMode;
import com.stockquant.server.agent.shadow.AgentShadowModels.SelectionResult;
import com.stockquant.server.agent.shadow.AgentShadowModels.SelectionSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;

@Service
public class AgentShadowSelectionService {

    private final AgentShadowRepository repository;

    public AgentShadowSelectionService(AgentShadowRepository repository) {
        this.repository = repository;
    }

    @Transactional(
            readOnly = true,
            isolation = Isolation.REPEATABLE_READ)
    public SelectionResult select(
            SelectionMode mode,
            List<String> explicitSymbols,
            int maxSymbols,
            LocalDate tradeDate
    ) {
        requireMaxSymbols(maxSymbols);
        if (mode == null) {
            throw new IllegalArgumentException(
                    "selectionMode is required");
        }
        List<SelectionEntry> entries = switch (mode) {
            case EXPLICIT -> explicit(explicitSymbols);
            case AUTO -> automatic(maxSymbols);
        };
        if (entries.size() > maxSymbols) {
            entries = List.copyOf(entries.subList(0, maxSymbols));
        }
        return new SelectionResult(
                hash(mode, tradeDate, maxSymbols, entries),
                entries);
    }

    public SelectionResult empty(
            SelectionMode mode,
            LocalDate tradeDate,
            int maxSymbols
    ) {
        requireMaxSymbols(maxSymbols);
        return new SelectionResult(
                hash(mode, tradeDate, maxSymbols, List.of()),
                List.of());
    }

    private static List<SelectionEntry> explicit(
            List<String> explicitSymbols
    ) {
        if (explicitSymbols == null
                || explicitSymbols.isEmpty()
                || explicitSymbols.size()
                > AgentShadowContracts.HARD_MAX_SYMBOLS) {
            throw new IllegalArgumentException(
                    "EXPLICIT selection requires 1 to 20 symbols");
        }
        List<String> normalized = explicitSymbols.stream()
                .map(value -> value == null ? "" : value.trim())
                .toList();
        if (normalized.stream().anyMatch(
                value -> !value.matches("^[0-9]{6}$"))) {
            throw new IllegalArgumentException(
                    "every shadow symbol must contain six digits");
        }
        List<String> sorted = normalized.stream()
                .distinct()
                .sorted()
                .toList();
        List<SelectionEntry> entries = new ArrayList<>();
        for (int index = 0; index < sorted.size(); index++) {
            String symbol = sorted.get(index);
            entries.add(new SelectionEntry(
                    index + 1,
                    symbol,
                    SelectionSource.EXPLICIT,
                    "explicit:symbol=" + symbol));
        }
        return List.copyOf(entries);
    }

    private List<SelectionEntry> automatic(int maxSymbols) {
        LinkedHashMap<String, AgentShadowRepository.SelectionCandidate>
                selected = new LinkedHashMap<>();
        for (var candidate : repository.currentPositionCandidates()) {
            validateDatabaseSymbol(candidate.symbol());
            selected.putIfAbsent(candidate.symbol(), candidate);
            if (selected.size() == maxSymbols) {
                break;
            }
        }
        if (selected.size() < maxSymbols) {
            repository.latestCompletedScanTaskId().ifPresent(taskId -> {
                for (var candidate
                        : repository.eligibleScanCandidates(taskId)) {
                    validateDatabaseSymbol(candidate.symbol());
                    selected.putIfAbsent(candidate.symbol(), candidate);
                    if (selected.size() == maxSymbols) {
                        break;
                    }
                }
            });
        }
        int order = 1;
        List<SelectionEntry> entries = new ArrayList<>();
        for (var candidate : selected.values()) {
            entries.add(new SelectionEntry(
                    order++,
                    candidate.symbol(),
                    candidate.source(),
                    candidate.sourceRef()));
        }
        return List.copyOf(entries);
    }

    private static String hash(
            SelectionMode mode,
            LocalDate tradeDate,
            int maxSymbols,
            List<SelectionEntry> entries
    ) {
        if (tradeDate == null) {
            throw new IllegalArgumentException(
                    "tradeDate is required");
        }
        StringBuilder canonical = new StringBuilder();
        canonical.append("contractVersion=")
                .append(AgentShadowContracts.SELECTION_VERSION)
                .append('\n');
        canonical.append("selectionMode=")
                .append(mode.name()).append('\n');
        canonical.append("tradeDate=")
                .append(tradeDate).append('\n');
        canonical.append("configuredMaxSymbols=")
                .append(maxSymbols).append('\n');
        for (SelectionEntry entry : entries) {
            canonical.append(entry.selectionOrder()).append('|')
                    .append(entry.symbol()).append('|')
                    .append(entry.selectionSource()).append('|')
                    .append(entry.selectionSourceRef()).append('\n');
        }
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.toString()
                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable", error);
        }
    }

    private static void requireMaxSymbols(int maxSymbols) {
        if (maxSymbols < 1
                || maxSymbols > AgentShadowContracts.HARD_MAX_SYMBOLS) {
            throw new IllegalArgumentException(
                    "maxSymbols must be within [1,20]");
        }
    }

    private static void validateDatabaseSymbol(String symbol) {
        if (symbol == null || !symbol.matches("^[0-9]{6}$")) {
            throw new IllegalStateException(
                    "shadow selection encountered an invalid database symbol");
        }
    }
}

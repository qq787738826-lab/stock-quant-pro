package com.stockquant.server.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.model.AgentModels.ContextSnapshot;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Service
public class AgentContextSnapshotService {

    public static final String CONTEXT_SCHEMA_VERSION = "1.0";
    private static final List<String> CONTEXT_SECTIONS = List.of(
            "security", "marketData", "marketBreadth", "scanResult", "technicalMetrics",
            "backtestContext", "securityEvents", "portfolioContext", "dataQualityContext"
    );

    private final ObjectMapper objectMapper;
    private final AgentContextHashService hashService;
    private final Clock clock;

    public AgentContextSnapshotService(ObjectMapper objectMapper, AgentContextHashService hashService) {
        this(objectMapper, hashService, Clock.systemUTC());
    }

    AgentContextSnapshotService(ObjectMapper objectMapper, AgentContextHashService hashService, Clock clock) {
        this.objectMapper = objectMapper;
        this.hashService = hashService;
        this.clock = clock;
    }

    public ContextSnapshot create(String symbol, LocalDate tradeDate) {
        Instant queriedAt = clock.instant();
        ObjectNode root = objectMapper.createObjectNode();
        for (String section : CONTEXT_SECTIONS) {
            ObjectNode unavailable = root.putObject(section);
            unavailable.put("available", false);
            unavailable.put("reason", "阶段1C-1尚未接入该只读上下文查询");
            unavailable.put("queriedAt", queriedAt.toString());
            ObjectNode queryScope = unavailable.putObject("queryScope");
            queryScope.put("symbol", symbol);
            queryScope.put("tradeDate", tradeDate.toString());
        }
        return new ContextSnapshot(CONTEXT_SCHEMA_VERSION, root, queriedAt, hashService.hash(root));
    }
}

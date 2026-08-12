package com.stockquant.server.agent.research;

import com.stockquant.server.agent.research.AgentResearchModels.AgentRole;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

/** Classpath-backed, immutable prompt catalog; no dynamic prompt platform. */
public final class AgentPromptCatalog {
    private static final Map<AgentRole, String> CHAMPION_RESOURCES = Map.of(
            AgentRole.RESEARCH_COORDINATOR,
            "agent-research/prompts/research-coordinator-v2.txt",
            AgentRole.DATA_ANALYST,
            "agent-research/prompts/data-analyst-v2.txt",
            AgentRole.MARKET_TECHNICAL,
            "agent-research/prompts/market-technical-v2.txt",
            AgentRole.STRATEGY_RESEARCH,
            "agent-research/prompts/strategy-research-v2.txt",
            AgentRole.RISK,
            "agent-research/prompts/risk-v2.txt",
            AgentRole.PORTFOLIO,
            "agent-research/prompts/portfolio-v2.txt",
            AgentRole.CRITIC_REVIEW,
            "agent-research/prompts/critic-review-v2.txt");

    private final Map<AgentRole, PromptDefinition> prompts;

    public AgentPromptCatalog() {
        this(CHAMPION_RESOURCES);
    }

    private AgentPromptCatalog(Map<AgentRole, String> resources) {
        EnumMap<AgentRole, PromptDefinition> loaded = new EnumMap<>(
                AgentRole.class);
        resources.forEach((role, resource) -> loaded.put(role,
                load(role, resource)));
        if (loaded.size() != AgentRole.values().length) {
            throw new IllegalStateException("M3_PROMPT_CATALOG_INCOMPLETE");
        }
        prompts = Map.copyOf(loaded);
    }

    /**
     * The one bounded M5 challenger.  It changes only Critic policy and is
     * never substituted for the default Champion catalog.
     */
    public static AgentPromptCatalog m5CriticCalibrationChallenger() {
        EnumMap<AgentRole, String> resources = new EnumMap<>(
                CHAMPION_RESOURCES);
        resources.put(AgentRole.CRITIC_REVIEW,
                "agent-research/prompts/critic-review-v3.txt");
        return new AgentPromptCatalog(Map.copyOf(resources));
    }

    public Map<AgentRole, String> versions() {
        EnumMap<AgentRole, String> values = new EnumMap<>(AgentRole.class);
        prompts.forEach((role, prompt) -> values.put(role,
                prompt.version()));
        return Map.copyOf(values);
    }

    public PromptDefinition prompt(AgentRole role) {
        PromptDefinition value = prompts.get(role);
        if (value == null) {
            throw new IllegalArgumentException("M3_PROMPT_ROLE_UNKNOWN");
        }
        return value;
    }

    private static PromptDefinition load(AgentRole role, String resource) {
        try (InputStream stream = AgentPromptCatalog.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("M3_PROMPT_RESOURCE_MISSING");
            }
            String text = new String(stream.readAllBytes(),
                    StandardCharsets.UTF_8).replace("\r\n", "\n").trim();
            String version = text.lines().findFirst().orElse("");
            if (!version.matches("PROMPT_VERSION=M3_[A-Z_]+_V[1-9][0-9]*")
                    || text.length() < 120 || text.length() > 4_000) {
                throw new IllegalStateException("M3_PROMPT_RESOURCE_INVALID");
            }
            return new PromptDefinition(role,
                    version.substring("PROMPT_VERSION=".length()), resource,
                    text, AgentResearchCanonical.sha256Text(text));
        } catch (IOException exception) {
            throw new IllegalStateException("M3_PROMPT_RESOURCE_READ_FAILED",
                    exception);
        }
    }

    public record PromptDefinition(
            AgentRole role,
            String version,
            String resource,
            String text,
            String fingerprint
    ) {
    }
}

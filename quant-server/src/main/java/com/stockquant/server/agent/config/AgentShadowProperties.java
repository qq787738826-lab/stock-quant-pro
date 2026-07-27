package com.stockquant.server.agent.config;

import com.stockquant.server.agent.shadow.AgentShadowContracts;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.LocalTime;

@Validated
@ConfigurationProperties(prefix = "stockquant.agent-team.shadow")
public class AgentShadowProperties {

    private boolean enabled;
    private boolean schedulerEnabled;

    @NotBlank
    private String ruleVersion = AgentShadowContracts.RULE_VERSION;

    @NotBlank
    private String cron = "0 50 16 * * MON-FRI";

    @NotBlank
    private String zone = "Asia/Shanghai";

    @NotNull
    private LocalTime safeWindowStart = LocalTime.of(16, 40);

    @NotNull
    private LocalTime safeWindowEnd = LocalTime.of(18, 30);

    @Min(1)
    @Max(20)
    private int maxSymbols = AgentShadowContracts.DEFAULT_MAX_SYMBOLS;

    @Min(1)
    @Max(2)
    private int maxConcurrency = 2;

    @NotNull
    private Duration itemTimeout = Duration.ofMinutes(5);

    @NotNull
    private Duration pollInterval = Duration.ofSeconds(2);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isSchedulerEnabled() {
        return schedulerEnabled;
    }

    public void setSchedulerEnabled(boolean schedulerEnabled) {
        this.schedulerEnabled = schedulerEnabled;
    }

    public String getRuleVersion() {
        return ruleVersion;
    }

    public void setRuleVersion(String ruleVersion) {
        this.ruleVersion = ruleVersion;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public String getZone() {
        return zone;
    }

    public void setZone(String zone) {
        this.zone = zone;
    }

    public LocalTime getSafeWindowStart() {
        return safeWindowStart;
    }

    public void setSafeWindowStart(LocalTime safeWindowStart) {
        this.safeWindowStart = safeWindowStart;
    }

    public LocalTime getSafeWindowEnd() {
        return safeWindowEnd;
    }

    public void setSafeWindowEnd(LocalTime safeWindowEnd) {
        this.safeWindowEnd = safeWindowEnd;
    }

    public int getMaxSymbols() {
        return maxSymbols;
    }

    public void setMaxSymbols(int maxSymbols) {
        this.maxSymbols = maxSymbols;
    }

    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    public void setMaxConcurrency(int maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }

    public Duration getItemTimeout() {
        return itemTimeout;
    }

    public void setItemTimeout(Duration itemTimeout) {
        this.itemTimeout = itemTimeout;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public void validateFrozenContract() {
        if (!AgentShadowContracts.RULE_VERSION.equals(ruleVersion)) {
            throw new IllegalArgumentException(
                    "shadow rule-version must be "
                            + AgentShadowContracts.RULE_VERSION);
        }
        if (!AgentShadowContracts.MARKET_ZONE.getId().equals(zone)) {
            throw new IllegalArgumentException(
                    "shadow zone must be Asia/Shanghai");
        }
        if (!safeWindowStart.isBefore(safeWindowEnd)) {
            throw new IllegalArgumentException(
                    "shadow safe-window-start must precede safe-window-end");
        }
        if (itemTimeout.isZero() || itemTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "shadow item-timeout must be positive");
        }
        if (pollInterval.isZero() || pollInterval.isNegative()
                || pollInterval.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException(
                    "shadow poll-interval must be within (0,30s]");
        }
    }
}

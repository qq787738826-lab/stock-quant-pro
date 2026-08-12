package com.stockquant.server.agent.shadowresearch;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.LocalTime;

/** Opt-in M4 daily dispatch settings; disabled by default. */
@Validated
@ConfigurationProperties(prefix = "stockquant.shadow-research.scheduler")
public class ShadowResearchScheduleProperties {
    private boolean enabled;
    @NotBlank
    private String cron = "0 20 17 * * MON-FRI";
    @NotBlank
    private String zone = "Asia/Shanghai";
    private LocalTime safeWindowStart = LocalTime.of(17, 15);
    private LocalTime safeWindowEnd = LocalTime.of(20, 0);
    @Min(1)
    @Max(20)
    private int maximumTushareRequests = 6;
    @Min(1)
    @Max(13)
    private int maximumModelCalls = 13;
    @Min(5)
    @Max(60)
    private int submitTimeoutSeconds = 30;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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

    public int getMaximumTushareRequests() {
        return maximumTushareRequests;
    }

    public void setMaximumTushareRequests(int maximumTushareRequests) {
        this.maximumTushareRequests = maximumTushareRequests;
    }

    public int getMaximumModelCalls() {
        return maximumModelCalls;
    }

    public void setMaximumModelCalls(int maximumModelCalls) {
        this.maximumModelCalls = maximumModelCalls;
    }

    public int getSubmitTimeoutSeconds() {
        return submitTimeoutSeconds;
    }

    public void setSubmitTimeoutSeconds(int submitTimeoutSeconds) {
        this.submitTimeoutSeconds = submitTimeoutSeconds;
    }

    void validate() {
        if (!"Asia/Shanghai".equals(zone)
                || safeWindowStart == null || safeWindowEnd == null
                || !safeWindowStart.isBefore(safeWindowEnd)) {
            throw new IllegalStateException("M4_SCHEDULER_CONFIGURATION_INVALID");
        }
    }
}

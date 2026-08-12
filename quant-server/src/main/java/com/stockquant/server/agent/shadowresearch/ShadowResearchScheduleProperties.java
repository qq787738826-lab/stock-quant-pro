package com.stockquant.server.agent.shadowresearch;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.LocalTime;
import java.math.BigDecimal;

/** User-approved M4 daily dispatch and monthly fail-closed budget settings. */
@Validated
@ConfigurationProperties(prefix = "stockquant.shadow-research.scheduler")
public class ShadowResearchScheduleProperties {
    private boolean enabled = true;
    @NotBlank
    private String cron = "0 20 17 * * MON-FRI";
    @NotBlank
    private String zone = "Asia/Shanghai";
    private LocalTime safeWindowStart = LocalTime.of(17, 15);
    private LocalTime safeWindowEnd = LocalTime.of(20, 0);
    @Min(1)
    @Max(20)
    private int maximumTushareRequests = 8;
    @Min(1)
    @Max(13)
    private int maximumModelCalls = 13;
    @Min(5)
    @Max(60)
    private int submitTimeoutSeconds = 30;
    @Min(1)
    @Max(150)
    private int monthlyTushareRequestLimit = 150;
    private BigDecimal monthlyBailianCostLimitCny = new BigDecimal("30.00");
    private BigDecimal projectMonthlyApiCostLimitCny =
            new BigDecimal("200.00");

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

    public int getMonthlyTushareRequestLimit() {
        return monthlyTushareRequestLimit;
    }

    public void setMonthlyTushareRequestLimit(int value) {
        monthlyTushareRequestLimit = value;
    }

    public BigDecimal getMonthlyBailianCostLimitCny() {
        return monthlyBailianCostLimitCny;
    }

    public void setMonthlyBailianCostLimitCny(BigDecimal value) {
        monthlyBailianCostLimitCny = value;
    }

    public BigDecimal getProjectMonthlyApiCostLimitCny() {
        return projectMonthlyApiCostLimitCny;
    }

    public void setProjectMonthlyApiCostLimitCny(BigDecimal value) {
        projectMonthlyApiCostLimitCny = value;
    }

    void validate() {
        if (!"Asia/Shanghai".equals(zone)
                || !enabled
                || !"0 20 17 * * MON-FRI".equals(cron)
                || maximumTushareRequests != 8
                || maximumModelCalls != 13
                || safeWindowStart == null || safeWindowEnd == null
                || !safeWindowStart.isBefore(safeWindowEnd)
                || monthlyTushareRequestLimit != 150
                || monthlyBailianCostLimitCny == null
                || monthlyBailianCostLimitCny.compareTo(
                new BigDecimal("30.00")) != 0
                || projectMonthlyApiCostLimitCny == null
                || projectMonthlyApiCostLimitCny.compareTo(
                new BigDecimal("200.00")) != 0) {
            throw new IllegalStateException("M4_SCHEDULER_CONFIGURATION_INVALID");
        }
    }
}

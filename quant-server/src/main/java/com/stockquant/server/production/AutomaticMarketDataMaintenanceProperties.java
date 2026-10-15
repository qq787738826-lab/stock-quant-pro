package com.stockquant.server.production;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.LocalTime;

/** Fixed after-close schedule for the data-only maintenance request. */
@Validated
@ConfigurationProperties(
        prefix = "stockquant.market-data-maintenance.scheduler")
public class AutomaticMarketDataMaintenanceProperties {
    static final String FIXED_CRON = "0 30 16 * * MON-FRI";
    static final String FIXED_ZONE = "Asia/Shanghai";

    private boolean enabled = false;
    @NotBlank
    private String cron = FIXED_CRON;
    @NotBlank
    private String zone = FIXED_ZONE;
    private LocalTime safeWindowStart = LocalTime.of(16, 25);
    private LocalTime safeWindowEnd = LocalTime.of(17, 10);
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

    public int getSubmitTimeoutSeconds() {
        return submitTimeoutSeconds;
    }

    public void setSubmitTimeoutSeconds(int submitTimeoutSeconds) {
        this.submitTimeoutSeconds = submitTimeoutSeconds;
    }

    void validate() {
        if (!enabled || !FIXED_CRON.equals(cron)
                || !FIXED_ZONE.equals(zone)
                || safeWindowStart == null || safeWindowEnd == null
                || !LocalTime.of(16, 25).equals(safeWindowStart)
                || !LocalTime.of(17, 10).equals(safeWindowEnd)
                || !safeWindowStart.isBefore(safeWindowEnd)
                || submitTimeoutSeconds != 30) {
            throw new IllegalStateException(
                    "AUTOMATIC_MARKET_DATA_MAINTENANCE_CONFIGURATION_INVALID");
        }
    }
}

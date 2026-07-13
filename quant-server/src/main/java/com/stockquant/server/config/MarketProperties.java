package com.stockquant.server.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "quant.market")
public class MarketProperties {

    @Min(0)
    private int scanDefaultLimit = 0;

    @Min(2)
    @Max(30)
    private int scanBatchSize = 12;

    @Min(10)
    @Max(200)
    private int scanResultLimit = 50;

    @Min(80)
    @Max(500)
    private int historyDays = 120;

    public int getScanDefaultLimit() {
        return scanDefaultLimit;
    }

    public void setScanDefaultLimit(int scanDefaultLimit) {
        this.scanDefaultLimit = scanDefaultLimit;
    }

    public int getScanBatchSize() {
        return scanBatchSize;
    }

    public void setScanBatchSize(int scanBatchSize) {
        this.scanBatchSize = scanBatchSize;
    }

    public int getScanResultLimit() {
        return scanResultLimit;
    }

    public void setScanResultLimit(int scanResultLimit) {
        this.scanResultLimit = scanResultLimit;
    }

    public int getHistoryDays() {
        return historyDays;
    }

    public void setHistoryDays(int historyDays) {
        this.historyDays = historyDays;
    }
}

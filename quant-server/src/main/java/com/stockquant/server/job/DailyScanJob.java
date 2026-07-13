package com.stockquant.server.job;

import com.stockquant.server.service.MarketDataCenterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DailyScanJob {
    private static final Logger log = LoggerFactory.getLogger(DailyScanJob.class);
    private final MarketDataCenterService service;

    public DailyScanJob(MarketDataCenterService service) {
        this.service = service;
    }

    @Scheduled(cron = "${quant.jobs.daily-scan-cron:0 10 16 * * MON-FRI}", zone = "Asia/Shanghai")
    public void scan() {
        try {
            if (service.hasRunningTask()) {
                log.info("已有扫描任务运行中，本次定时任务跳过");
                return;
            }
            long taskId = service.startScan(0, 12, 50);
            log.info("每日全市场扫描任务已创建，taskId={}", taskId);
        } catch (Exception e) {
            log.error("每日扫描任务创建失败", e);
        }
    }
}

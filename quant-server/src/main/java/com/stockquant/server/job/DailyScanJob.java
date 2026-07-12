package com.stockquant.server.job;

import com.stockquant.server.service.SignalService;
import org.slf4j.Logger; import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DailyScanJob {
    private static final Logger log= LoggerFactory.getLogger(DailyScanJob.class); private final SignalService service;
    public DailyScanJob(SignalService service) { this.service=service; }
    @Scheduled(cron="${quant.jobs.daily-scan-cron:0 10 16 * * MON-FRI}", zone="Asia/Shanghai")
    public void scan() { try { log.info("每日扫描完成，共生成 {} 个候选信号", service.dailySignals(10).size()); } catch (Exception e) { log.error("每日扫描失败",e); } }
}

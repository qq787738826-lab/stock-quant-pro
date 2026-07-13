package com.stockquant.server.job;

import com.stockquant.server.service.PortfolioService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PortfolioRiskJob {

    private static final Logger log = LoggerFactory.getLogger(PortfolioRiskJob.class);
    private final PortfolioService service;

    public PortfolioRiskJob(PortfolioService service) {
        this.service = service;
    }

    @Scheduled(cron = "${quant.jobs.portfolio-risk-cron:0 30 16 * * MON-FRI}", zone = "Asia/Shanghai")
    public void check() {
        try {
            var result = service.refreshAndCheckRisk();
            log.info("每日持仓风险检查完成：{}", result);
        } catch (Exception error) {
            log.error("每日持仓风险检查失败", error);
        }
    }
}

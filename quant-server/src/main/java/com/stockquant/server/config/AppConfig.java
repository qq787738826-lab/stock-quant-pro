package com.stockquant.server.config;

import com.stockquant.core.backtest.BacktestEngine;
import com.stockquant.core.risk.RiskManager;
import com.stockquant.core.strategy.MultiFactorShortTermStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

import org.springframework.core.task.TaskExecutor;

@Configuration
public class AppConfig {
    @Bean MultiFactorShortTermStrategy strategy() { return new MultiFactorShortTermStrategy(); }
    @Bean RiskManager riskManager() { return new RiskManager(); }
    @Bean BacktestEngine backtestEngine() { return new BacktestEngine(); }
    @Bean RestClient.Builder restClientBuilder() { return RestClient.builder(); }

    @Bean(name = "marketScanExecutor")
    TaskExecutor marketScanExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(2);
        executor.setThreadNamePrefix("market-scan-");
        executor.initialize();
        return executor;
    }
}

package com.stockquant.server.config;

import com.stockquant.core.backtest.BacktestEngine;
import com.stockquant.core.risk.RiskManager;
import com.stockquant.core.strategy.MultiFactorShortTermStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

@Configuration
public class AppConfig {

    @Bean
    MultiFactorShortTermStrategy strategy() {
        return new MultiFactorShortTermStrategy();
    }

    @Bean
    RiskManager riskManager() {
        return new RiskManager();
    }

    @Bean
    BacktestEngine backtestEngine() {
        return new BacktestEngine();
    }

    @Bean
    RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    @Bean(name = "marketScanExecutor")
    TaskExecutor marketScanExecutor() {
        return singleThreadExecutor("market-scan-", 2);
    }

    @Bean(name = "marketDataUpdateExecutor")
    TaskExecutor marketDataUpdateExecutor() {
        return singleThreadExecutor("market-update-", 1);
    }

    @Bean(name = "scanBacktestExecutor")
    TaskExecutor scanBacktestExecutor() {
        return singleThreadExecutor("scan-backtest-", 1);
    }

    private TaskExecutor singleThreadExecutor(String prefix, int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(prefix);
        executor.initialize();
        return executor;
    }
}

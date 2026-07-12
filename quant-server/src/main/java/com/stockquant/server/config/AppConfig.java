package com.stockquant.server.config;

import com.stockquant.core.backtest.BacktestEngine;
import com.stockquant.core.risk.RiskManager;
import com.stockquant.core.strategy.MultiFactorShortTermStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class AppConfig {
    @Bean MultiFactorShortTermStrategy strategy() { return new MultiFactorShortTermStrategy(); }
    @Bean RiskManager riskManager() { return new RiskManager(); }
    @Bean BacktestEngine backtestEngine() { return new BacktestEngine(); }
    @Bean RestClient.Builder restClientBuilder() { return RestClient.builder(); }
}

package com.stockquant.server.production;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AutomaticMarketDataMaintenanceProperties.class)
public class AutomaticMarketDataMaintenanceConfiguration {
}

package com.stockquant.server.agent.shadowresearch;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers only the opt-in M4 scheduler contract. */
@Configuration
@EnableConfigurationProperties(ShadowResearchScheduleProperties.class)
public class ShadowResearchConfiguration {
}

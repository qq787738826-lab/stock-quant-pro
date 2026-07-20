package com.stockquant.server.agent.temporal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
class TemporalClockConfiguration {

    static final String CLOCK_BEAN = "agentTemporalClock";

    @Bean(CLOCK_BEAN)
    Clock agentTemporalClock() {
        return Clock.systemUTC();
    }
}

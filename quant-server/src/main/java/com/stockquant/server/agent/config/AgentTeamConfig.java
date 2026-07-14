package com.stockquant.server.agent.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(AgentTeamProperties.class)
public class AgentTeamConfig {

    @Bean(name = "agentTeamExecutor")
    TaskExecutor agentTeamExecutor(AgentTeamProperties properties) {
        AgentTeamProperties.Executor settings = properties.getExecutor();
        if (settings.getMaxPoolSize() < settings.getCorePoolSize()) {
            throw new IllegalArgumentException("agent team maxPoolSize不能小于corePoolSize");
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(settings.getCorePoolSize());
        executor.setMaxPoolSize(settings.getMaxPoolSize());
        executor.setQueueCapacity(settings.getQueueCapacity());
        executor.setThreadNamePrefix("agent-team-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    @Bean
    @Qualifier("agentTeamRestClient")
    RestClient agentTeamRestClient(AgentTeamProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.toIntExact(properties.getConnectTimeout().toMillis()));
        requestFactory.setReadTimeout(Math.toIntExact(properties.getReadTimeout().toMillis()));
        String baseUrl = properties.getBaseUrl().endsWith("/")
                ? properties.getBaseUrl().substring(0, properties.getBaseUrl().length() - 1)
                : properties.getBaseUrl();
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
}

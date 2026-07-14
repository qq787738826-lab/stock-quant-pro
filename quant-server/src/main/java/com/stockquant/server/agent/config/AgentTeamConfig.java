package com.stockquant.server.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
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
    RestClient agentTeamRestClient(
            AgentTeamProperties properties,
            RestClient.Builder builder,
            ObjectMapper objectMapper
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.toIntExact(properties.getConnectTimeout().toMillis()));
        requestFactory.setReadTimeout(Math.toIntExact(properties.getReadTimeout().toMillis()));
        String baseUrl = properties.getBaseUrl().endsWith("/")
                ? properties.getBaseUrl().substring(0, properties.getBaseUrl().length() - 1)
                : properties.getBaseUrl();
        return builder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .messageConverters(converters -> {
                    boolean replaced = false;
                    for (int index = 0; index < converters.size(); index++) {
                        if (converters.get(index) instanceof MappingJackson2HttpMessageConverter) {
                            converters.set(index, new MappingJackson2HttpMessageConverter(objectMapper));
                            replaced = true;
                        }
                    }
                    if (!replaced) {
                        converters.add(new MappingJackson2HttpMessageConverter(objectMapper));
                    }
                })
                .build();
    }
}

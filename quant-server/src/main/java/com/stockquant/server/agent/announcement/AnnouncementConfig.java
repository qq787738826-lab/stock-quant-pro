package com.stockquant.server.agent.announcement;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(AnnouncementProperties.class)
public class AnnouncementConfig {

    @Bean
    @Qualifier("announcementProviderRestClient")
    RestClient announcementProviderRestClient(
            AnnouncementProperties properties,
            RestClient.Builder builder,
            ObjectMapper objectMapper
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(
                Math.toIntExact(properties.getConnectTimeout().toMillis()));
        requestFactory.setReadTimeout(
                Math.toIntExact(properties.getReadTimeout().toMillis()));
        String baseUrl = properties.getBaseUrl().endsWith("/")
                ? properties.getBaseUrl().substring(0, properties.getBaseUrl().length() - 1)
                : properties.getBaseUrl();
        return builder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .messageConverters(converters -> {
                    for (int index = 0; index < converters.size(); index++) {
                        if (converters.get(index)
                                instanceof MappingJackson2HttpMessageConverter) {
                            converters.set(
                                    index,
                                    new MappingJackson2HttpMessageConverter(objectMapper));
                        }
                    }
                })
                .build();
    }
}

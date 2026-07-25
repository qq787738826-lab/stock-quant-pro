package com.stockquant.server.agent.announcement;

import com.stockquant.server.agent.announcement.AnnouncementProviderModels.ProviderRequest;
import com.stockquant.server.agent.announcement.AnnouncementProviderModels.ProviderResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class HttpAnnouncementProviderClient implements AnnouncementProviderClient {

    private final RestClient restClient;

    public HttpAnnouncementProviderClient(
            @Qualifier("announcementProviderRestClient") RestClient restClient
    ) {
        this.restClient = restClient;
    }

    @Override
    public ProviderResponse fetch(ProviderRequest request) {
        try {
            ProviderResponse response = restClient.post()
                    .uri("/providers/akshare/cninfo/announcements")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(ProviderResponse.class);
            if (response == null) {
                throw new IllegalStateException("AKShare公告Provider返回空响应");
            }
            return response;
        } catch (RestClientResponseException error) {
            throw new IllegalStateException(
                    "AKShare公告Provider返回HTTP " + error.getStatusCode().value(), error);
        } catch (RestClientException error) {
            throw new IllegalStateException(
                    "AKShare公告Provider连接、超时或JSON响应失败", error);
        }
    }
}

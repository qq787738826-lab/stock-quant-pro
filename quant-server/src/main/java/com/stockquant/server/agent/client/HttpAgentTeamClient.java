package com.stockquant.server.agent.client;

import com.stockquant.server.agent.config.AgentTeamProperties;
import com.stockquant.server.agent.exception.AgentTeamClientException;
import com.stockquant.server.agent.model.AgentModels.AgentTeamRequest;
import com.stockquant.server.agent.model.AgentModels.AgentTeamResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class HttpAgentTeamClient implements AgentTeamClient {

    private final RestClient restClient;
    private final AgentTeamProperties properties;

    public HttpAgentTeamClient(
            @Qualifier("agentTeamRestClient") RestClient restClient,
            AgentTeamProperties properties
    ) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public AgentTeamResponse analyze(AgentTeamRequest request) {
        if (!properties.isEnabled()) {
            throw new AgentTeamClientException(AgentTeamClientException.Kind.DISABLED,
                    "agent team client disabled");
        }
        try {
            AgentTeamResponse response = restClient.post()
                    .uri("/agents/team/analyze")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(AgentTeamResponse.class);
            if (response == null) {
                throw new AgentTeamClientException(AgentTeamClientException.Kind.SERVICE_UNAVAILABLE,
                        "agent team service returned empty response");
            }
            return response;
        } catch (RestClientResponseException error) {
            throw new AgentTeamClientException(
                    AgentTeamClientException.Kind.SERVICE_UNAVAILABLE,
                    "agent team service returned HTTP " + error.getStatusCode().value(),
                    error
            );
        } catch (RestClientException error) {
            throw new AgentTeamClientException(AgentTeamClientException.Kind.SERVICE_UNAVAILABLE,
                    "agent team service connection, timeout or JSON error", error);
        }
    }
}

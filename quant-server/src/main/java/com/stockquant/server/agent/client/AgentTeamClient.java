package com.stockquant.server.agent.client;

import com.stockquant.server.agent.model.AgentModels.AgentTeamRequest;
import com.stockquant.server.agent.model.AgentModels.AgentTeamResponse;

public interface AgentTeamClient {
    AgentTeamResponse analyze(AgentTeamRequest request);
}

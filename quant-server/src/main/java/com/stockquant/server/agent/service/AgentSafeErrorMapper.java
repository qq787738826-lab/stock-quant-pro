package com.stockquant.server.agent.service;

import com.stockquant.server.agent.exception.AgentResponseValidationException;
import com.stockquant.server.agent.exception.AgentTeamClientException;
import org.springframework.stereotype.Service;

@Service
public class AgentSafeErrorMapper {

    public String publicMessage(Throwable error) {
        if (error instanceof AgentTeamClientException clientError) {
            return clientError.kind() == AgentTeamClientException.Kind.DISABLED
                    ? "智能体团队功能未启用"
                    : "智能体分析服务暂时不可用";
        }
        if (error instanceof AgentResponseValidationException) {
            return "智能体响应校验失败";
        }
        return "智能体团队执行失败";
    }
}

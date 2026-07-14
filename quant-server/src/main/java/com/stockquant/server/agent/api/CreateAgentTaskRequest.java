package com.stockquant.server.agent.api;

import com.stockquant.server.agent.model.AgentTypes.ExecutionMode;
import com.stockquant.server.agent.model.AgentTypes.TriggerType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CreateAgentTaskRequest(
        @NotBlank(message = "symbol不能为空")
        @Pattern(regexp = "^[0-9]{6}$", message = "symbol必须是6位数字")
        String symbol,
        @NotNull(message = "tradeDate不能为空") LocalDate tradeDate,
        @NotNull(message = "executionMode不能为空") ExecutionMode executionMode,
        @NotBlank(message = "ruleVersion不能为空")
        @Size(max = 64, message = "ruleVersion长度不能超过64字符")
        String ruleVersion,
        boolean forceRefresh,
        @NotNull(message = "triggerType不能为空") TriggerType triggerType
) {
    public CreateAgentTaskRequest {
        if (ruleVersion != null) {
            ruleVersion = ruleVersion.trim();
        }
    }
}

package com.stockquant.server.agent.shadow.api;

import com.stockquant.server.agent.shadow.AgentShadowModels.SelectionMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public record CreateShadowBatchRequest(
        @NotNull LocalDate tradeDate,
        @NotNull SelectionMode selectionMode,
        @Size(max = 20)
        List<
                @Pattern(regexp = "^[0-9]{6}$")
                String> explicitSymbols,
        @Min(1) @Max(20) Integer maxSymbols,
        @NotBlank @Size(max = 128) String createdBy
) {
}

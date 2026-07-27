package com.stockquant.server.agent.shadow.api;

import com.stockquant.server.agent.shadow.AgentShadowModels.ReviewLabel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateShadowReviewRequest(
        @NotNull ReviewLabel label,
        @NotBlank @Size(max = 4000) String note,
        @NotBlank @Size(max = 128) String reviewer,
        @Positive Long supersedesReviewId
) {
}

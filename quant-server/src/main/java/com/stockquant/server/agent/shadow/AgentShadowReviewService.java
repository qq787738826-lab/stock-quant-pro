package com.stockquant.server.agent.shadow;

import com.stockquant.server.agent.shadow.AgentShadowModels.ReviewLabel;
import com.stockquant.server.agent.shadow.AgentShadowModels.ShadowItem;
import com.stockquant.server.agent.shadow.AgentShadowModels.ShadowReview;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AgentShadowReviewService {

    private final AgentShadowRepository repository;

    public AgentShadowReviewService(AgentShadowRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public ShadowReview add(
            long itemId,
            ReviewLabel label,
            String note,
            String reviewer,
            Long supersedesReviewId
    ) {
        ShadowItem item = requireItem(itemId);
        if (!item.terminal()) {
            throw new IllegalArgumentException(
                    "only terminal shadow items may be reviewed");
        }
        if (label == null) {
            throw new IllegalArgumentException(
                    "review label is required");
        }
        String safeNote = requireText(note, "note", 4000);
        String safeReviewer = requireText(
                reviewer, "reviewer", 128);
        if (supersedesReviewId != null
                && supersedesReviewId <= 0) {
            throw new IllegalArgumentException(
                    "supersedesReviewId must be positive");
        }
        return repository.insertReview(
                item.batchId(),
                item.id(),
                label,
                safeNote,
                safeReviewer,
                supersedesReviewId);
    }

    @Transactional(readOnly = true)
    public List<ShadowReview> reviews(long itemId) {
        requireItem(itemId);
        return repository.findReviews(itemId);
    }

    private ShadowItem requireItem(long itemId) {
        if (itemId <= 0) {
            throw new IllegalArgumentException(
                    "itemId must be positive");
        }
        return repository.findItem(itemId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "shadow item does not exist: " + itemId));
    }

    private static String requireText(
            String value,
            String name,
            int maxLength
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    name + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    name + " exceeds " + maxLength + " characters");
        }
        return normalized;
    }
}

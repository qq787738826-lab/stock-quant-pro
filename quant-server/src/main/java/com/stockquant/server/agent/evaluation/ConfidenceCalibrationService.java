package com.stockquant.server.agent.evaluation;

import com.stockquant.server.agent.evaluation.AgentEvaluationModels.CalibrationBin;
import com.stockquant.server.agent.evaluation.AgentEvaluationModels.ConfidenceCalibration;
import com.stockquant.server.agent.evaluation.AgentEvaluationModels.EvaluationStatus;
import com.stockquant.server.agent.evaluation.AgentEvaluationModels.ShadowOutcomeEvaluation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Minimal Brier/ECE calibration. Abstention is reported but not penalized. */
public final class ConfidenceCalibrationService {
    private static final int SCALE = 8;
    private static final BigDecimal MAXIMUM_BRIER_SCORE =
            new BigDecimal("0.25");
    private static final BigDecimal MAXIMUM_ECE = new BigDecimal("0.20");

    public ConfidenceCalibration evaluate(
            List<ShadowOutcomeEvaluation> outcomes
    ) {
        List<ShadowOutcomeEvaluation> eligible = outcomes.stream()
                .filter(value -> !value.abstention()
                        && value.directionScorable()).toList();
        int abstentions = Math.toIntExact(outcomes.stream()
                .filter(ShadowOutcomeEvaluation::abstention).count());
        List<CalibrationBin> bins = List.of(
                bin(eligible, new BigDecimal("0.0"), new BigDecimal("0.4"),
                        true),
                bin(eligible, new BigDecimal("0.4"), new BigDecimal("0.7"),
                        false),
                bin(eligible, new BigDecimal("0.7"), BigDecimal.ONE, false));
        BigDecimal brier = average(eligible.stream().map(value -> {
            BigDecimal observed = value.directionHit()
                    ? BigDecimal.ONE : BigDecimal.ZERO;
            BigDecimal error = value.confidence().subtract(observed);
            return error.multiply(error);
        }).toList());
        BigDecimal ece = BigDecimal.ZERO;
        if (!eligible.isEmpty()) {
            for (CalibrationBin bin : bins) {
                BigDecimal weight = BigDecimal.valueOf(bin.sampleCount())
                        .divide(BigDecimal.valueOf(eligible.size()), SCALE,
                                RoundingMode.HALF_UP);
                ece = ece.add(bin.meanConfidence()
                        .subtract(bin.observedHitRate()).abs()
                        .multiply(weight));
            }
        }
        EvaluationStatus status;
        if (eligible.size() < AgentEvaluationModels.MINIMUM_SHADOW_SAMPLE) {
            status = EvaluationStatus.INSUFFICIENT_SAMPLE;
        } else if (brier.compareTo(MAXIMUM_BRIER_SCORE) > 0
                || ece.compareTo(MAXIMUM_ECE) > 0) {
            status = EvaluationStatus.FAIL;
        } else {
            status = EvaluationStatus.PASS;
        }
        return new ConfidenceCalibration(status, eligible.size(), abstentions,
                brier, ece.setScale(SCALE, RoundingMode.HALF_UP), bins);
    }

    private static CalibrationBin bin(
            List<ShadowOutcomeEvaluation> values,
            BigDecimal lower,
            BigDecimal upper,
            boolean lowerBin
    ) {
        List<ShadowOutcomeEvaluation> selected = values.stream()
                .filter(value -> (lowerBin
                        ? value.confidence().compareTo(lower) >= 0
                        : value.confidence().compareTo(lower) > 0)
                        && value.confidence().compareTo(upper) <= 0).toList();
        BigDecimal confidence = average(selected.stream()
                .map(ShadowOutcomeEvaluation::confidence).toList());
        BigDecimal hitRate = selected.isEmpty() ? BigDecimal.ZERO
                : BigDecimal.valueOf(selected.stream()
                        .filter(ShadowOutcomeEvaluation::directionHit).count())
                .divide(BigDecimal.valueOf(selected.size()), SCALE,
                        RoundingMode.HALF_UP);
        return new CalibrationBin(lower, upper, selected.size(), confidence,
                hitRate);
    }

    private static BigDecimal average(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return BigDecimal.ZERO.setScale(SCALE);
        }
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO,
                BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), SCALE,
                RoundingMode.HALF_UP);
    }
}

package com.stockquant.server.agent.temporal;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.Set;

/** JSONB-compatible semantic equality without floating-point conversion. */
final class TemporalJsonSemantics {

    private TemporalJsonSemantics() {}

    static boolean same(JsonNode left, JsonNode right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        if (left.isNumber() || right.isNumber()) {
            return left.isNumber()
                    && right.isNumber()
                    && left.decimalValue().compareTo(right.decimalValue()) == 0;
        }
        if (left.isObject() || right.isObject()) {
            if (!left.isObject() || !right.isObject() || left.size() != right.size()) {
                return false;
            }
            Set<String> leftNames = new HashSet<>();
            left.fieldNames().forEachRemaining(leftNames::add);
            Set<String> rightNames = new HashSet<>();
            right.fieldNames().forEachRemaining(rightNames::add);
            if (!leftNames.equals(rightNames)) {
                return false;
            }
            return leftNames.stream().allMatch(name -> same(left.get(name), right.get(name)));
        }
        if (left.isArray() || right.isArray()) {
            if (!left.isArray() || !right.isArray() || left.size() != right.size()) {
                return false;
            }
            for (int index = 0; index < left.size(); index++) {
                if (!same(left.get(index), right.get(index))) {
                    return false;
                }
            }
            return true;
        }
        return left.getNodeType() == right.getNodeType() && left.equals(right);
    }
}

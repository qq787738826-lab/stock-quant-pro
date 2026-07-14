package com.stockquant.server.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.exception.AgentTeamException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import java.util.TreeSet;

@Service
public class AgentContextHashService {

    private static final Set<String> VOLATILE_FIELDS = Set.of(
            "taskId", "runId", "runIds", "requestedAt", "generatedAt",
            "contextGeneratedAt", "queriedAt"
    );

    private final ObjectMapper objectMapper;

    public AgentContextHashService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String hash(JsonNode context) {
        try {
            JsonNode normalized = normalize(context);
            byte[] bytes = objectMapper.writeValueAsString(normalized).getBytes(StandardCharsets.UTF_8);
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException error) {
            throw new AgentTeamException("无法计算智能体上下文哈希", error);
        }
    }

    JsonNode normalize(JsonNode node) {
        if (node == null || node.isNull()) {
            return JsonNodeFactory.instance.nullNode();
        }
        if (node.isObject()) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            TreeSet<String> names = new TreeSet<>();
            node.fieldNames().forEachRemaining(names::add);
            names.stream().filter(name -> !VOLATILE_FIELDS.contains(name))
                    .forEach(name -> result.set(name, normalize(node.get(name))));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> result.add(normalize(item)));
            return result;
        }
        if (node.isFloatingPointNumber()) {
            BigDecimal stable = node.decimalValue().stripTrailingZeros();
            return JsonNodeFactory.instance.numberNode(stable);
        }
        if (node.isIntegralNumber()) {
            return JsonNodeFactory.instance.numberNode(node.bigIntegerValue());
        }
        return node.deepCopy();
    }
}

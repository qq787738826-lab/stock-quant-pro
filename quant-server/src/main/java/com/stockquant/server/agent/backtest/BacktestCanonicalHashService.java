package com.stockquant.server.agent.backtest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.exception.AgentTeamException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class BacktestCanonicalHashService {

    private static final DateTimeFormatter INSTANT_MICROS =
            new java.time.format.DateTimeFormatterBuilder().appendInstant(6).toFormatter();

    private final ObjectMapper objectMapper;

    public BacktestCanonicalHashService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String hash(JsonNode value) {
        return sha256(canonicalText(value));
    }

    public String canonicalText(JsonNode value) {
        StringBuilder result = new StringBuilder();
        append(value, result);
        return result.toString();
    }

    public static String sha256(String canonicalText) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalText.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new AgentTeamException("运行环境不支持SHA-256", error);
        }
    }

    public static Instant microsecondInstant(Instant value) {
        if (value == null) throw new IllegalArgumentException("时间不能为空");
        return value.truncatedTo(ChronoUnit.MICROS);
    }

    public static String formatInstant(Instant value) {
        return INSTANT_MICROS.format(microsecondInstant(value));
    }

    private void append(JsonNode value, StringBuilder target) {
        if (value == null || value.isNull()) {
            target.append("null");
            return;
        }
        if (value.isObject()) {
            appendObject(value, target);
            return;
        }
        if (value.isArray()) {
            target.append('[');
            for (int index = 0; index < value.size(); index++) {
                if (index > 0) target.append(',');
                append(value.get(index), target);
            }
            target.append(']');
            return;
        }
        if (value.isTextual()) {
            appendQuoted(normalize(value.textValue()), target);
            return;
        }
        if (value.isNumber()) {
            appendNumber(value, target);
            return;
        }
        if (value.isBoolean()) {
            target.append(value.booleanValue() ? "true" : "false");
            return;
        }
        throw new IllegalArgumentException(
                "BACKTEST_CANONICAL_V1不支持JSON类型：" + value.getNodeType());
    }

    private void appendObject(JsonNode value, StringBuilder target) {
        List<Field> fields = new ArrayList<>();
        Set<String> normalizedNames = new HashSet<>();
        value.fields().forEachRemaining(entry -> {
            String name = normalize(entry.getKey());
            if (!normalizedNames.add(name)) {
                throw new IllegalArgumentException(
                        "BACKTEST_CANONICAL_V1字段NFC规范化后重复：" + name);
            }
            fields.add(new Field(name, entry.getValue()));
        });
        fields.sort(Comparator.comparing(Field::name));
        target.append('{');
        for (int index = 0; index < fields.size(); index++) {
            if (index > 0) target.append(',');
            Field field = fields.get(index);
            appendQuoted(field.name(), target);
            target.append(':');
            append(field.value(), target);
        }
        target.append('}');
    }

    private void appendNumber(JsonNode value, StringBuilder target) {
        if (value.isFloatingPointNumber() && !Double.isFinite(value.doubleValue())) {
            throw new IllegalArgumentException(
                    "BACKTEST_CANONICAL_V1禁止NaN和Infinity");
        }
        BigDecimal number;
        try {
            number = value.decimalValue();
        } catch (ArithmeticException | NumberFormatException error) {
            throw new IllegalArgumentException(
                    "BACKTEST_CANONICAL_V1禁止非法数值", error);
        }
        if (number.signum() == 0) {
            target.append('0');
        } else {
            target.append(number.stripTrailingZeros().toPlainString());
        }
    }

    private void appendQuoted(String value, StringBuilder target) {
        try {
            target.append(objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException error) {
            throw new AgentTeamException("无法序列化canonical文本", error);
        }
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC);
    }

    private record Field(String name, JsonNode value) {
    }
}

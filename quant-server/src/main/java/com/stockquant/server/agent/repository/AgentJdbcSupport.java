package com.stockquant.server.agent.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.exception.AgentTeamException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;

final class AgentJdbcSupport {

    private AgentJdbcSupport() {}

    static JsonNode readJson(ObjectMapper objectMapper, String value) {
        try {
            return value == null ? null : objectMapper.readTree(value);
        } catch (JsonProcessingException error) {
            throw new AgentTeamException("数据库中的智能体JSON无法解析", error);
        }
    }

    static String writeJson(ObjectMapper objectMapper, Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new AgentTeamException("智能体JSON无法序列化", error);
        }
    }

    static Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        return (Instant) value;
    }

    static String textArray(Iterable<String> values) {
        StringBuilder result = new StringBuilder("{");
        boolean first = true;
        for (String value : values) {
            if (!first) {
                result.append(',');
            }
            first = false;
            result.append('"').append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return result.append('}').toString();
    }

    static String longArray(Iterable<Long> values) {
        StringBuilder result = new StringBuilder("{");
        boolean first = true;
        for (Long value : values) {
            if (!first) {
                result.append(',');
            }
            first = false;
            result.append(value);
        }
        return result.append('}').toString();
    }
}

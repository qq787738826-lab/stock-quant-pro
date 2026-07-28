package com.stockquant.server.agent.marketfacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Local-only redaction and whitelist gate for committable synthetic fixtures. */
@Service
public class OfflineFixtureSanitizer {

    public static final String SCHEMA_VERSION = "OFFLINE_PROVIDER_FIXTURE_V1";
    private static final Set<String> SENSITIVE_TOKENS = Set.of(
            "authorization", "cookie", "token", "session", "password",
            "passwd", "secret", "username", "user_name", "account",
            "apikey", "api_key", "machinepath", "machine_path",
            "home", "homedir", "personalinfo", "personal_info"
    );
    private static final Pattern SENSITIVE_VALUE = Pattern.compile(
            "(?i)(?:"
                    + "(?:authorization|cookie|token|session|password|passwd"
                    + "|secret|api[_-]?key)\\s*[:=]"
                    + "|https?://[^/\\s:@]+:[^@\\s]+@"
                    + "|[a-z]:\\\\(?:users|documents and settings)\\\\"
                    + "|/(?:home|users)/[^/\\s]+/"
                    + ")");

    private final ObjectMapper objectMapper;
    private final PitMarketFactsCanonicalService canonical;

    public OfflineFixtureSanitizer(
            ObjectMapper objectMapper,
            PitMarketFactsCanonicalService canonical
    ) {
        this.objectMapper = objectMapper;
        this.canonical = canonical;
    }

    public SanitizedFixture sanitize(
            JsonNode input,
            Set<String> allowedTopLevelFields
    ) {
        if (input == null || !input.isObject()) {
            throw new IllegalArgumentException("fixture input must be an object");
        }
        Set<String> whitelist = Set.copyOf(allowedTopLevelFields);
        ObjectNode sanitized = objectMapper.createObjectNode();
        Iterator<String> names = input.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (whitelist.contains(name) && !sensitive(name)) {
                sanitized.set(name, sanitizeNode(input.get(name)));
            }
        }
        sanitized.put("fixtureSchemaVersion", SCHEMA_VERSION);
        sanitized.put("providerContractVersion",
                PitMarketFactsContracts.PROVIDER_CONTRACT_VERSION);
        rejectSensitive(sanitized);
        return new SanitizedFixture(
                sanitized,
                canonical.canonicalText(sanitized),
                canonical.hash(sanitized));
    }

    public void rejectSensitive(JsonNode value) {
        scan(value, new HashSet<>());
    }

    private JsonNode sanitizeNode(JsonNode value) {
        if (value == null || value.isNull() || value.isValueNode()) {
            return value == null ? objectMapper.nullNode() : value.deepCopy();
        }
        if (value.isArray()) {
            ArrayNode output = objectMapper.createArrayNode();
            value.forEach(item -> output.add(sanitizeNode(item)));
            return output;
        }
        if (value.isObject()) {
            ObjectNode output = objectMapper.createObjectNode();
            value.fields().forEachRemaining(entry -> {
                if (!sensitive(entry.getKey())) {
                    output.set(entry.getKey(), sanitizeNode(entry.getValue()));
                }
            });
            return output;
        }
        throw new IllegalArgumentException("unsupported fixture JSON node");
    }

    private void scan(JsonNode value, Set<JsonNode> visited) {
        if (value == null || !visited.add(value)) return;
        if (value.isObject()) {
            value.fields().forEachRemaining(entry -> {
                if (sensitive(entry.getKey())) {
                    throw new IllegalArgumentException(
                            "sensitive fixture field remains: " + entry.getKey());
                }
                scan(entry.getValue(), visited);
            });
        } else if (value.isArray()) {
            value.forEach(item -> scan(item, visited));
        } else if (value.isTextual()
                && SENSITIVE_VALUE.matcher(value.asText()).find()) {
            throw new IllegalArgumentException(
                    "sensitive fixture value remains");
        }
    }

    private static boolean sensitive(String name) {
        String normalized = name.replace("-", "")
                .replace("_", "")
                .toLowerCase(Locale.ROOT);
        return SENSITIVE_TOKENS.stream()
                .map(token -> token.replace("_", ""))
                .anyMatch(normalized::contains);
    }

    public record SanitizedFixture(
            ObjectNode value,
            String canonicalText,
            String sha256
    ) {
    }
}

package com.stockquant.server.agent.announcement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockquant.server.agent.announcement.AnnouncementProviderModels.ProviderRecord;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AnnouncementCanonicalService {

    private static final Set<String> TRACKING_KEYS = Set.of(
            "from", "source", "spm", "track", "tracking");
    private static final List<String> EXPLICIT_ID_KEYS = List.of(
            "announcement_id",
            "announcement_no",
            "announcementid",
            "announcementno",
            "bulletin_id",
            "bulletinid");
    private static final Pattern EXPLICIT_ID =
            Pattern.compile("^[A-Za-z0-9._-]+$");
    private static final Pattern PATH_ID =
            Pattern.compile("^[A-Za-z0-9_-]*[0-9][A-Za-z0-9._-]{5,}$");

    private final ObjectMapper objectMapper;

    public AnnouncementCanonicalService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AnnouncementFact prepare(
            ProviderRecord record,
            Instant observedAt,
            String batchVersion
    ) {
        if (record == null
                || record.symbol() == null
                || !record.symbol().matches("^[0-9]{6}$")
                || blank(record.securityName())
                || blank(record.title())
                || record.securityName().length() > 128
                || record.title().length() > 1024
                || record.reportedPublishDate() == null
                || record.rawFields() == null
                || !record.rawFields().isObject()) {
            throw new IllegalArgumentException("AKShare公告记录字段非法");
        }
        SourceIdentity identity = sourceIdentity(record.sourceUrl());
        AnnouncementFact provisional = new AnnouncementFact(
                record.symbol(),
                record.securityName().trim(),
                record.title().trim(),
                record.reportedPublishDate(),
                record.sourceUrl().trim(),
                identity.normalizedUrl(),
                sha256(identity.normalizedUrl()),
                identity.sourceAnnouncementId(),
                identity.strength(),
                observedAt.truncatedTo(ChronoUnit.MICROS),
                null,
                null,
                record.rawFields().deepCopy());
        String canonicalHash = sha256(canonicalText(provisional));
        String observationVersion = sha256(String.join(
                "\n",
                "ANNOUNCEMENT_OBSERVATION_V1",
                batchVersion,
                provisional.sourceAnnouncementId(),
                canonicalHash,
                provisional.firstObservedAt().toString()));
        return provisional.withHashes(canonicalHash, observationVersion);
    }

    public String canonicalText(AnnouncementFact fact) {
        TreeMap<String, Object> value = new TreeMap<>();
        value.put("assuranceLevel", AnnouncementContracts.ASSURANCE_LEVEL);
        value.put("contractVersion", AnnouncementContracts.CANONICAL_CONTRACT_VERSION);
        value.put("formalEligible", false);
        value.put("normalizedSourceUrl", fact.normalizedSourceUrl());
        value.put("pitVerified", false);
        value.put("providerContractVersion",
                AnnouncementContracts.PROVIDER_CONTRACT_VERSION);
        value.put("reportedPublishDate", fact.reportedPublishDate().toString());
        value.put("reportedPublishTimePrecision",
                AnnouncementContracts.PUBLISH_TIME_PRECISION);
        value.put("revisionRelationshipGuaranteed", false);
        value.put("securityName", fact.securityName());
        value.put("sourceAnnouncementId", fact.sourceAnnouncementId());
        value.put("sourceCode", AnnouncementContracts.SOURCE_CODE);
        value.put("sourceIdentityStrength", fact.sourceIdentityStrength());
        value.put("symbol", fact.symbol());
        value.put("title", fact.title());
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("公告canonical JSON序列化失败", error);
        }
    }

    public boolean hashMatches(AnnouncementFact fact) {
        return fact.canonicalContentHash() != null
                && fact.canonicalContentHash().equals(sha256(canonicalText(fact)));
    }

    public SourceIdentity sourceIdentity(String sourceUrl) {
        String normalized = normalizeUrl(sourceUrl);
        URI uri = URI.create(normalized);
        TreeMap<String, String> query = new TreeMap<>();
        for (QueryParameter parameter : queryParameters(uri.getRawQuery())) {
            query.put(parameter.key().toLowerCase(Locale.ROOT), parameter.value());
        }
        for (String key : EXPLICIT_ID_KEYS) {
            String value = query.get(key);
            if (value != null && EXPLICIT_ID.matcher(value).matches()) {
                return new SourceIdentity("CNINFO:" + value, "CNINFO_ID", normalized);
            }
        }
        String path = uri.getPath();
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        int extension = fileName.lastIndexOf('.');
        String baseName = extension > 0 ? fileName.substring(0, extension) : fileName;
        if (PATH_ID.matcher(baseName).matches()) {
            return new SourceIdentity("CNINFO:" + baseName, "CNINFO_ID", normalized);
        }
        return new SourceIdentity(
                "CNINFO_URL_SHA256:" + sha256(normalized),
                "URL_DERIVED",
                normalized);
    }

    public String normalizeUrl(String sourceUrl) {
        if (blank(sourceUrl)) {
            throw new IllegalArgumentException("公告URL不能为空");
        }
        try {
            URI source = new URI(sourceUrl.trim());
            String scheme = source.getScheme() == null
                    ? null : source.getScheme().toLowerCase(Locale.ROOT);
            String host = source.getHost() == null
                    ? null : source.getHost().toLowerCase(Locale.ROOT);
            if (!Set.of("http", "https").contains(scheme) || blank(host)) {
                throw new IllegalArgumentException("公告URL必须是HTTP或HTTPS");
            }
            int port = source.getPort();
            if (scheme.equals("http") && port == 80 || scheme.equals("https") && port == 443) {
                port = -1;
            }
            List<QueryParameter> parameters = queryParameters(source.getRawQuery()).stream()
                    .filter(value -> {
                        String key = value.key().toLowerCase(Locale.ROOT);
                        return !key.startsWith("utm_") && !TRACKING_KEYS.contains(key);
                    })
                    .sorted(Comparator.comparing(QueryParameter::key)
                            .thenComparing(QueryParameter::value))
                    .toList();
            String query = parameters.isEmpty() ? null : parameters.stream()
                    .map(value -> encode(value.key()) + "=" + encode(value.value()))
                    .reduce((left, right) -> left + "&" + right)
                    .orElse(null);
            String path = blank(source.getPath()) ? "/" : source.getPath();
            return new URI(scheme, null, host, port, path, query, null).toASCIIString();
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("公告URL格式非法", error);
        }
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("运行环境缺少SHA-256", error);
        }
    }

    private static List<QueryParameter> queryParameters(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return List.of();
        }
        List<QueryParameter> result = new ArrayList<>();
        for (String item : rawQuery.split("&", -1)) {
            int separator = item.indexOf('=');
            String key = separator < 0 ? item : item.substring(0, separator);
            String value = separator < 0 ? "" : item.substring(separator + 1);
            result.add(new QueryParameter(decode(key), decode(value)));
        }
        return result;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record QueryParameter(String key, String value) {
    }

    public record SourceIdentity(
            String sourceAnnouncementId,
            String strength,
            String normalizedUrl
    ) {
    }

    public record AnnouncementFact(
            String symbol,
            String securityName,
            String title,
            java.time.LocalDate reportedPublishDate,
            String sourceUrl,
            String normalizedSourceUrl,
            String sourceUrlHash,
            String sourceAnnouncementId,
            String sourceIdentityStrength,
            Instant firstObservedAt,
            String canonicalContentHash,
            String observationVersion,
            JsonNode rawPayload
    ) {
        AnnouncementFact withHashes(String canonicalHash, String version) {
            return new AnnouncementFact(
                    symbol,
                    securityName,
                    title,
                    reportedPublishDate,
                    sourceUrl,
                    normalizedSourceUrl,
                    sourceUrlHash,
                    sourceAnnouncementId,
                    sourceIdentityStrength,
                    firstObservedAt,
                    canonicalHash,
                    version,
                    rawPayload);
        }
    }
}

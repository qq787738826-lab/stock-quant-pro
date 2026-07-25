package com.stockquant.server.agent.announcement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockquant.server.agent.announcement.AnnouncementCanonicalService.AnnouncementFact;
import com.stockquant.server.agent.announcement.AnnouncementCanonicalService.SourceIdentity;
import com.stockquant.server.agent.announcement.AnnouncementProviderModels.ProviderRecord;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnnouncementCanonicalServiceTest {

    private static final String BATCH =
            "ANNOUNCEMENT_BATCH_V1:11111111-1111-1111-1111-111111111111";
    private static final Instant OBSERVED_AT =
            Instant.parse("2025-01-03T02:03:04.123456Z");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AnnouncementCanonicalService service =
            new AnnouncementCanonicalService(mapper);

    @Test
    void matchesCommittedGoldenVector() throws Exception {
        JsonNode input = mapper.readTree(resource(
                "agent/announcement-canonical-v1-input.json"));
        ObjectNode raw = mapper.createObjectNode();
        raw.put("代码", input.path("symbol").asText());
        raw.put("简称", input.path("securityName").asText());
        raw.put("公告标题", input.path("title").asText());
        raw.put("公告时间", input.path("reportedPublishDate").asText());
        raw.put("公告链接", input.path("normalizedSourceUrl").asText());
        AnnouncementFact fact = service.prepare(new ProviderRecord(
                input.path("symbol").asText(),
                input.path("securityName").asText(),
                input.path("title").asText(),
                LocalDate.parse(input.path("reportedPublishDate").asText()),
                input.path("normalizedSourceUrl").asText(),
                raw), OBSERVED_AT, BATCH);
        assertEquals(resource(
                        "agent/announcement-canonical-v1-canonical.txt").stripTrailing(),
                service.canonicalText(fact));
        assertEquals(resource(
                        "agent/announcement-canonical-v1-sha256.txt").strip(),
                fact.canonicalContentHash());
        assertTrue(service.hashMatches(fact));
    }

    @Test
    void normalizesUrlPrefersExplicitIdentityAndFallsBackToUrlHash() {
        SourceIdentity explicit = service.sourceIdentity(
                "HTTPS://Static.CNINFO.COM.CN:443/finalpage/x.pdf"
                        + "?utm_source=x&announcementId=1212345678#fragment");
        assertEquals("CNINFO:1212345678", explicit.sourceAnnouncementId());
        assertEquals("CNINFO_ID", explicit.strength());
        assertEquals(
                "https://static.cninfo.com.cn/finalpage/x.pdf"
                        + "?announcementId=1212345678",
                explicit.normalizedUrl());

        SourceIdentity fallback = service.sourceIdentity(
                "https://www.cninfo.com.cn/path/report"
                        + "?b=2&utm_medium=x&a=1#fragment");
        assertEquals("URL_DERIVED", fallback.strength());
        assertTrue(fallback.sourceAnnouncementId()
                .matches("^CNINFO_URL_SHA256:[0-9a-f]{64}$"));
        assertEquals(
                "https://www.cninfo.com.cn/path/report?a=1&b=2",
                fallback.normalizedUrl());
        assertEquals(
                "http://static.cninfo.com.cn/finalpage/x.pdf",
                service.normalizeUrl(
                        "HTTP://STATIC.CNINFO.COM.CN:80/finalpage/x.pdf"));
    }

    @Test
    void changedContentGetsChangedHashAndObservationVersion() {
        ProviderRecord first = record("重大诉讼公告");
        ProviderRecord second = record("重大诉讼进展公告");
        AnnouncementFact firstFact = service.prepare(first, OBSERVED_AT, BATCH);
        AnnouncementFact secondFact = service.prepare(second, OBSERVED_AT, BATCH);
        assertNotEquals(
                firstFact.canonicalContentHash(),
                secondFact.canonicalContentHash());
        assertNotEquals(
                firstFact.observationVersion(),
                secondFact.observationVersion());
    }

    @Test
    void rejectsMissingOrNonHttpIdentityInputs() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.sourceIdentity("file:///tmp/a.pdf"));
        for (String sourceUrl : List.of(
                "https://example.com/a.pdf",
                "https://cninfo.com.cn.evil.example/a.pdf",
                "https://evil-cninfo.com.cn/a.pdf",
                "https://static.cninfo.com.cn:8443/a.pdf",
                "http://static.cninfo.com.cn:443/a.pdf",
                "https://user@static.cninfo.com.cn/a.pdf")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> service.sourceIdentity(sourceUrl),
                    sourceUrl);
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> service.prepare(
                        new ProviderRecord(
                                "1", "", "", null, "https://example.cn/a", null),
                        OBSERVED_AT,
                        BATCH));
    }

    private ProviderRecord record(String title) {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("代码", "000001");
        raw.put("简称", "平安银行");
        raw.put("公告标题", title);
        raw.put("公告时间", "2025-01-02");
        raw.put("公告链接",
                "https://static.cninfo.com.cn/finalpage/2025-01-02/1212345678.pdf");
        return new ProviderRecord(
                "000001",
                "平安银行",
                title,
                LocalDate.of(2025, 1, 2),
                "https://static.cninfo.com.cn/finalpage/2025-01-02/1212345678.pdf",
                raw);
    }

    private static String resource(String name) throws Exception {
        try (InputStream stream = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(name)) {
            if (stream == null) throw new IllegalStateException(name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

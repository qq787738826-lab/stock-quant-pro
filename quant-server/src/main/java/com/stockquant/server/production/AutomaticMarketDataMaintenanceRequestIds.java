package com.stockquant.server.production;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Objects;

/** Stable one-request identity per China trading date and production SHA. */
public final class AutomaticMarketDataMaintenanceRequestIds {
    private static final DateTimeFormatter REQUEST_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                    .withZone(ZoneOffset.UTC);
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final String SCOPE =
            "V1_AUTOMATIC_DAILY_MARKET_DATA_MAINTENANCE";

    private AutomaticMarketDataMaintenanceRequestIds() {
    }

    public static String forDate(LocalDate tradeDate, String gitCommit) {
        Objects.requireNonNull(tradeDate, "tradeDate");
        if (gitCommit == null || !gitCommit.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException(
                    "AUTOMATIC_MARKET_DATA_MAINTENANCE_GIT_INVALID");
        }
        String timestamp = REQUEST_TIME.format(tradeDate
                .atTime(LocalTime.of(16, 30)).atZone(SHANGHAI));
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(
                    (SCOPE + '|' + tradeDate + '|' + gitCommit)
                            .getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(
                    "AUTOMATIC_MARKET_DATA_MAINTENANCE_SHA256_UNAVAILABLE",
                    error);
        }
        String suffix = HexFormat.of().withUpperCase().formatHex(
                digest, 0, 6);
        return "SQHB_" + timestamp + '_' + suffix;
    }
}

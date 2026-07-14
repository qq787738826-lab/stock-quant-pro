package com.stockquant.server.agent.repository;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AgentJdbcSupportTest {

    @Test
    void convertsInstantToUtcOffsetDateTimeWithoutChangingTheMoment() {
        Instant instant = OffsetDateTime.parse("2026-07-14T13:02:00+08:00").toInstant();

        OffsetDateTime converted = AgentJdbcSupport.timestamptz(instant);

        assertEquals(ZoneOffset.UTC, converted.getOffset());
        assertEquals(instant, converted.toInstant());
        assertEquals(Instant.parse("2026-07-14T05:02:00Z"), converted.toInstant());
    }

    @Test
    void supportsNullAndExistingDatabaseReadRepresentations() {
        Instant instant = Instant.parse("2026-07-14T05:02:00Z");

        assertNull(AgentJdbcSupport.timestamptz(null));
        assertEquals(instant, AgentJdbcSupport.instant(instant.atOffset(ZoneOffset.ofHours(8))));
        assertEquals(instant, AgentJdbcSupport.instant(Timestamp.from(instant)));
    }
}

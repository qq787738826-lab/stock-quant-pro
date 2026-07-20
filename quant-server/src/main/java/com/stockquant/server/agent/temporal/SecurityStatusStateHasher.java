package com.stockquant.server.agent.temporal;

import com.stockquant.server.agent.temporal.TemporalModels.PublishSecurityStatusVersionCommand;

import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Produces a deterministic hash of business status and its valid-time interval. */
@Component
public class SecurityStatusStateHasher {

    private static final String FORMAT_VERSION = "SECURITY_STATUS_STATE_V1";

    public String hash(PublishSecurityStatusVersionCommand value) {
        TemporalValidation.required(value, "value");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            field(digest, FORMAT_VERSION);
            field(digest, value.symbol());
            field(digest, value.exchange().name());
            field(digest, value.board());
            field(digest, Boolean.toString(value.listed()));
            field(digest, Boolean.toString(value.active()));
            field(digest, Boolean.toString(value.st()));
            field(digest, value.validFrom().toString());
            field(digest, value.validTo() == null ? null : value.validTo().toString());
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static void field(MessageDigest digest, String value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}

package com.stockquant.server.agent.marketfacts;

import com.stockquant.server.agent.marketfacts.CompositeSecretProvider.Mode;
import com.stockquant.server.agent.marketfacts.SecretProvider.SecretValue;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Local-only credential health probe. It never constructs an HTTP client or
 * connects to a database, and it never renders a credential or its digest.
 */
public final class TushareCredentialHealthProbe {

    private TushareCredentialHealthProbe() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        if (args == null || args.length != 0) {
            writeFailure("STOCK_QUANT_SECRET_PROBE_ARGUMENT_INVALID");
            return 20;
        }
        char[] first = null;
        char[] second = null;
        try (SecretProvider provider = CompositeSecretProvider.formalLocal(
                Mode.WINDOWS_CREDENTIAL_MANAGER);
             SecretValue firstValue = provider.readTushareToken();
             SecretValue secondValue = provider.readTushareToken()) {
            first = firstValue.copy();
            second = secondValue.copy();
            Inspection inspection = inspect(first, second);
            System.out.println("STOCK_QUANT_SECRET_READ=SUCCESS");
            System.out.println("STOCK_QUANT_SECRET_LENGTH="
                    + inspection.length());
            System.out.println("STOCK_QUANT_SECRET_FORMAT="
                    + inspection.format());
            System.out.println("STOCK_QUANT_SECRET_FINGERPRINT_STABLE="
                    + inspection.fingerprintStable());
            System.out.println("STOCK_QUANT_PROVIDER_CALLS=0");
            return 0;
        } catch (Throwable error) {
            writeFailure(safeCode(error));
            return 20;
        } finally {
            clear(first);
            clear(second);
        }
    }

    static Inspection inspect(char[] first, char[] second) {
        if (first == null || second == null) {
            throw new IllegalArgumentException(
                    "STOCK_QUANT_SECRET_PROBE_VALUE_INVALID");
        }
        String format = format(first);
        byte[] firstDigest = digest(first);
        byte[] secondDigest = digest(second);
        try {
            return new Inspection(
                    first.length,
                    format,
                    first.length == second.length
                            && MessageDigest.isEqual(firstDigest, secondDigest));
        } finally {
            Arrays.fill(firstDigest, (byte) 0);
            Arrays.fill(secondDigest, (byte) 0);
        }
    }

    private static String format(char[] value) {
        if (value.length < 16 || value.length > 128) {
            return "INVALID_LENGTH";
        }
        for (char character : value) {
            if (Character.isWhitespace(character)) {
                return "INVALID_WHITESPACE";
            }
            if (character > 0x7f || !Character.isLetterOrDigit(character)) {
                return "INVALID_CHARACTERS";
            }
        }
        return "VALID_TRANSPORT_FORMAT";
    }

    private static byte[] digest(char[] value) {
        byte[] encoded = new byte[value.length * Character.BYTES];
        ByteBuffer buffer = ByteBuffer.wrap(encoded);
        for (char character : value) {
            buffer.putChar(character);
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(encoded);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    private static void clear(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }

    private static String safeCode(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null
                    && message.matches("[A-Z][A-Z0-9_]{7,127}")) {
                return message;
            }
            current = current.getCause();
        }
        return "STOCK_QUANT_SECRET_PROBE_FAILED";
    }

    private static void writeFailure(String reason) {
        System.err.println("STOCK_QUANT_SECRET_READ=FAILED");
        System.err.println("STOCK_QUANT_SECRET_PROBE_REASON=" + reason);
        System.err.println("STOCK_QUANT_PROVIDER_CALLS=0");
    }

    record Inspection(
            int length,
            String format,
            boolean fingerprintStable
    ) {
        Inspection {
            if (length < 1 || length > 1_280
                    || format == null
                    || !format.matches("[A-Z][A-Z0-9_]{2,63}")) {
                throw new IllegalArgumentException(
                        "STOCK_QUANT_SECRET_PROBE_RESULT_INVALID");
            }
        }
    }
}

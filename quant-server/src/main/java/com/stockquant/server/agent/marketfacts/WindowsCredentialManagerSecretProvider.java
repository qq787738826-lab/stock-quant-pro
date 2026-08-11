package com.stockquant.server.agent.marketfacts;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.W32APIOptions;
import com.sun.jna.win32.StdCallLibrary;

import java.util.Arrays;
import java.util.Objects;

/** Reads only the fixed allow-listed Generic Credentials for the current user. */
public final class WindowsCredentialManagerSecretProvider
        implements SecretProvider {
    private static final int CRED_TYPE_GENERIC = 1;
    private static final int ERROR_NOT_FOUND = 1168;
    private static final int MAXIMUM_CREDENTIAL_BLOB_BYTES = 2_560;

    private final CredentialReader reader;

    public WindowsCredentialManagerSecretProvider() {
        this(requireWindows(), new NativeCredentialReader());
    }

    WindowsCredentialManagerSecretProvider(
            boolean windows,
            CredentialReader reader
    ) {
        if (!windows) {
            throw new IllegalStateException(
                    "STOCK_QUANT_WINDOWS_CREDENTIAL_MANAGER_REQUIRED");
        }
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    @Override
    public SecretValue read(SecretTarget target) {
        Objects.requireNonNull(target, "target");
        char[] value;
        try {
            value = reader.read(target.credentialTarget());
        } catch (RuntimeException | LinkageError error) {
            String message = error.getMessage();
            if (message != null
                    && message.matches("[A-Z][A-Z0-9_]{7,127}")) {
                throw error;
            }
            throw new IllegalStateException(
                    "STOCK_QUANT_CREDENTIAL_READ_FAILED");
        }
        try {
            if (value == null) {
                throw new IllegalStateException(
                        "STOCK_QUANT_CREDENTIAL_VALUE_INVALID");
            }
            return new SecretValue(value);
        } finally {
            if (value != null) {
                Arrays.fill(value, '\0');
            }
        }
    }

    @Override
    public String toString() {
        return "WindowsCredentialManagerSecretProvider[REDACTED]";
    }

    private static boolean requireWindows() {
        String osName = System.getProperty("os.name", "");
        if (!osName.toLowerCase(java.util.Locale.ROOT).startsWith("windows")) {
            throw new IllegalStateException(
                    "STOCK_QUANT_WINDOWS_CREDENTIAL_MANAGER_REQUIRED");
        }
        return true;
    }

    @FunctionalInterface
    interface CredentialReader {
        char[] read(String credentialTarget);
    }

    private static final class NativeCredentialReader
            implements CredentialReader {
        private final Advapi32Credentials api = Native.load(
                "Advapi32", Advapi32Credentials.class,
                W32APIOptions.UNICODE_OPTIONS);

        @Override
        public char[] read(String credentialTarget) {
            SecretTarget.requireCredentialTarget(credentialTarget);
            PointerByReference reference = new PointerByReference();
            if (!api.CredRead(
                    credentialTarget, CRED_TYPE_GENERIC, 0, reference)) {
                int error = Native.getLastError();
                throw new IllegalStateException(error == ERROR_NOT_FOUND
                        ? "STOCK_QUANT_CREDENTIAL_NOT_FOUND"
                        : "STOCK_QUANT_CREDENTIAL_READ_FAILED");
            }
            Pointer credentialPointer = reference.getValue();
            if (credentialPointer == null) {
                throw new IllegalStateException(
                        "STOCK_QUANT_CREDENTIAL_READ_FAILED");
            }
            Credential credential = new Credential(credentialPointer);
            try {
                credential.read();
                int byteCount = credential.credentialBlobSize;
                if (credential.type != CRED_TYPE_GENERIC
                        || credential.credentialBlob == null
                        || byteCount <= 0 || byteCount % Character.BYTES != 0
                        || byteCount > MAXIMUM_CREDENTIAL_BLOB_BYTES) {
                    throw new IllegalStateException(
                            "STOCK_QUANT_CREDENTIAL_VALUE_INVALID");
                }
                return credential.credentialBlob.getCharArray(
                        0, byteCount / Character.BYTES);
            } finally {
                if (credential.credentialBlob != null
                        && credential.credentialBlobSize > 0
                        && credential.credentialBlobSize
                        <= MAXIMUM_CREDENTIAL_BLOB_BYTES) {
                    credential.credentialBlob.setMemory(
                            0, credential.credentialBlobSize, (byte) 0);
                }
                api.CredFree(credentialPointer);
            }
        }
    }

    private interface Advapi32Credentials extends StdCallLibrary {
        boolean CredRead(
                String targetName,
                int type,
                int flags,
                PointerByReference credential);

        void CredFree(Pointer credential);
    }

    @Structure.FieldOrder({
            "flags", "type", "targetName", "comment", "lastWritten",
            "credentialBlobSize", "credentialBlob", "persist",
            "attributeCount", "attributes", "targetAlias", "userName"
    })
    /** JNA-reflective layout only; it is never returned by the provider API. */
    public static final class Credential extends Structure {
        public int flags;
        public int type;
        public Pointer targetName;
        public Pointer comment;
        public FileTime lastWritten = new FileTime();
        public int credentialBlobSize;
        public Pointer credentialBlob;
        public int persist;
        public int attributeCount;
        public Pointer attributes;
        public Pointer targetAlias;
        public Pointer userName;

        public Credential(Pointer pointer) {
            super(pointer, ALIGN_DEFAULT);
        }
    }

    @Structure.FieldOrder({"lowDateTime", "highDateTime"})
    /** JNA requires public construction for an embedded native structure. */
    public static final class FileTime extends Structure {
        public FileTime() {
        }

        public int lowDateTime;
        public int highDateTime;
    }
}

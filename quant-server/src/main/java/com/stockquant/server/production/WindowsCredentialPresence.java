package com.stockquant.server.production;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.util.Set;

/** Presence-only probe. It never dereferences a credential blob. */
final class WindowsCredentialPresence {
    private static final Set<String> TARGETS = Set.of(
            "StockQuant/ResearchDbPassword",
            "StockQuant/TushareToken",
            "StockQuant/BailianApiKey");
    private static final int CRED_TYPE_GENERIC = 1;
    private static final int ERROR_NOT_FOUND = 1168;
    private static final Api API = Native.load("Advapi32", Api.class,
            W32APIOptions.UNICODE_OPTIONS);

    private WindowsCredentialPresence() {
    }

    static boolean present(String target) {
        if (!TARGETS.contains(target)) {
            throw new IllegalArgumentException("M6_CREDENTIAL_TARGET_FORBIDDEN");
        }
        PointerByReference reference = new PointerByReference();
        if (!API.CredRead(target, CRED_TYPE_GENERIC, 0, reference)) {
            if (Native.getLastError() == ERROR_NOT_FOUND) return false;
            throw new IllegalStateException("M6_CREDENTIAL_STATUS_FAILED");
        }
        Pointer pointer = reference.getValue();
        if (pointer == null) {
            throw new IllegalStateException("M6_CREDENTIAL_STATUS_FAILED");
        }
        API.CredFree(pointer);
        return true;
    }

    private interface Api extends StdCallLibrary {
        boolean CredRead(String target, int type, int flags,
                         PointerByReference credential);

        void CredFree(Pointer credential);
    }
}

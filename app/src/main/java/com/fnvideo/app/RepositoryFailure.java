package com.fnvideo.app;

import java.io.IOException;

/** App-owned error classification; UI does not inspect NAS codes or message text. */
public class RepositoryFailure extends IOException {
    public enum Kind { AUTHENTICATION_REQUIRED, PERMISSION_DENIED, OTHER }
    public final Kind kind;

    public RepositoryFailure(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public static boolean requiresLogin(Throwable error) {
        // A broken external cause chain must not hang the UI.
        for (int depth = 0; error != null && depth < 16; depth++, error = error.getCause()) {
            if (error instanceof RepositoryFailure
                    && ((RepositoryFailure) error).kind == Kind.AUTHENTICATION_REQUIRED) {
                return true;
            }
        }
        return false;
    }
}

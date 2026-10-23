package com.vanvatcorporation.drivekit;

import java.io.IOException;

/** An API answer that is not a success (status/code/message from the server) or a network failure (status 0). */
public class DriveException extends IOException {
    public final int status;
    public final String code;

    public DriveException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public DriveException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
        this.code = null;
    }

    public boolean isUnauthorized() { return status == 401; }
    public boolean isLimit() { return status == 429; }

    /** New terms were published and the user has not accepted them yet. */
    public boolean isTosRequired() { return status == 403 && "tos_required".equals(code); }
}

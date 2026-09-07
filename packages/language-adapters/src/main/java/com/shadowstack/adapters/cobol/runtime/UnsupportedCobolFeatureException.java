package com.shadowstack.adapters.cobol.runtime;

/**
 * Fail-closed signal when a COBOL feature is outside the supported rehost surface.
 */
public final class UnsupportedCobolFeatureException extends RuntimeException {

    public UnsupportedCobolFeatureException(String message) {
        super(message);
    }

    public UnsupportedCobolFeatureException(String message, Throwable cause) {
        super(message, cause);
    }
}

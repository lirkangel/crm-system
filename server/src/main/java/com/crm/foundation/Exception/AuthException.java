package com.crm.foundation.Exception;

/**
 * Thrown by AuthService when an authentication or token operation fails.
 * Carries a machine-readable {@code code} so the controller (or ErrorHandler)
 * can return a structured 401 without knowing failure details itself.
 */
public class AuthException extends RuntimeException {

    private final String code;

    public AuthException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

package com.crm.foundation.Plugin;

/**
 * Thrown by a plugin to fail a request with a specific HTTP status and a stable,
 * machine-readable error code; the foundation renders it as problem+json.
 */
public class PluginProblem extends RuntimeException {

    private final int status;
    private final String code;

    public PluginProblem(int status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}

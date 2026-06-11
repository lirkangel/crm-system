package com.crm.foundation.Plugin;

/**
 * A plugin package failed validation (unsafe ZIP, missing/unparseable
 * {@code plugin.yaml}, or missing required manifest fields). The message is the
 * human-readable rejection reason recorded against the plugin.
 */
public class InvalidPluginPackageException extends RuntimeException {

    public InvalidPluginPackageException(String message) {
        super(message);
    }

    public InvalidPluginPackageException(String message, Throwable cause) {
        super(message, cause);
    }
}

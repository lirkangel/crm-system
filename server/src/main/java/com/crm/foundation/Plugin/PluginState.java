package com.crm.foundation.Plugin;

/**
 * Lifecycle states persisted in {@code plugin_registry.state}.
 *
 * <pre>
 * REGISTERED ──activate──▶ ACTIVE ◀──activate── DISABLED / LOAD_FAILED
 *     │                      │                        ▲
 *     │                      └──────disable───────────┘
 *     └─(any state)── markLoadFailed ──▶ LOAD_FAILED
 *      (any state)── markUninstallPending ──▶ UNINSTALL_PENDING (terminal;
 *                          row removed by the uninstall cleanup job)
 * </pre>
 */
public enum PluginState {
    /** Row created; plugin discovered but not yet loaded. */
    REGISTERED,
    /** Loaded and running (or eligible to load on next restart). */
    ACTIVE,
    /** Administratively switched off; skipped at startup. */
    DISABLED,
    /** Validation/migration/load failed — see {@code error_message}. */
    LOAD_FAILED,
    /** Marked for removal; cleanup job drops schema and registry row. */
    UNINSTALL_PENDING
}

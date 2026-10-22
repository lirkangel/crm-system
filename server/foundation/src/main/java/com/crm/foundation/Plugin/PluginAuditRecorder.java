package com.crm.foundation.Plugin;

import com.crm.foundation.Audit.AuditPayload;
import com.crm.foundation.Service.AuditService;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.UUID;

/** {@link AuditRecorder} bound to one plugin id; stamps the current user and request IP. */
public class PluginAuditRecorder implements AuditRecorder {

    private static final String SEVERITY_INFO = "INFO";

    private final String pluginId;
    private final AuditService audit;
    private final CurrentUser user;

    public PluginAuditRecorder(String pluginId, AuditService audit, CurrentUser user) {
        this.pluginId = pluginId;
        this.audit = audit;
        this.user = user;
    }

    @Override
    public void record(String entityType, UUID entityId, String op, String beforeJson, String afterJson) {
        audit.record(new AuditPayload(
            Instant.now(), user.id().orElse(null), sourceIp(), pluginId,
            entityType, entityId, op, beforeJson, afterJson, SEVERITY_INFO));
    }

    private static String sourceIp() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet
            ? servlet.getRequest().getRemoteAddr()
            : null;
    }
}

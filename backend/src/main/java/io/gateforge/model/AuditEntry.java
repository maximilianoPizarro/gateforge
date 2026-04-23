package io.gateforge.model;

import java.time.Instant;

public record AuditEntry(
    String id,
    Instant timestamp,
    String action,
    String resourceKind,
    String resourceName,
    String namespace,
    String yamlBefore,
    String yamlAfter,
    String performedBy,
    String targetClusterId
) {
    public AuditEntry(String id, Instant timestamp, String action, String resourceKind,
                      String resourceName, String namespace, String yamlBefore, String yamlAfter,
                      String performedBy) {
        this(id, timestamp, action, resourceKind, resourceName, namespace,
             yamlBefore, yamlAfter, performedBy, "local");
    }
}

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
    String performedBy
) {}

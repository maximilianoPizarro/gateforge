package io.gateforge.service;

import io.gateforge.model.AuditEntry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class AuditService {

    @Inject
    MigrationService migrationService;

    public AuditEntry record(String action, String kind, String name, String namespace,
                             String yamlBefore, String yamlAfter) {
        AuditEntry entry = new AuditEntry(
                UUID.randomUUID().toString().substring(0, 8),
                Instant.now(), action, kind, name, namespace,
                yamlBefore, yamlAfter, "gateforge-agent"
        );
        migrationService.addAuditEntry(entry);
        return entry;
    }

    public List<AuditEntry> getAll() {
        return migrationService.getAuditLog();
    }
}

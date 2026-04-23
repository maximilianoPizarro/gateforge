package io.gateforge.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "audit_entries")
public class AuditEntryEntity extends PanacheEntityBase {

    @Id
    public String id;

    public Instant timestamp;
    public String action;

    @Column(name = "resource_kind")
    public String resourceKind;

    @Column(name = "resource_name")
    public String resourceName;

    public String namespace;

    @Column(name = "yaml_before", columnDefinition = "TEXT")
    public String yamlBefore;

    @Column(name = "yaml_after", columnDefinition = "TEXT")
    public String yamlAfter;

    @Column(name = "performed_by")
    public String performedBy;

    @Column(name = "target_cluster_id")
    public String targetClusterId;
}

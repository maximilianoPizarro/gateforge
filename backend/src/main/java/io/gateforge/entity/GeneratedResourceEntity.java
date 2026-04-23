package io.gateforge.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

@Entity
@Table(name = "generated_resources")
public class GeneratedResourceEntity extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    public String kind;
    public String name;
    public String namespace;

    @Column(columnDefinition = "TEXT")
    public String yaml;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id")
    public MigrationPlanEntity plan;
}

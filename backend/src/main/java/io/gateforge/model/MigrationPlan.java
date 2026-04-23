package io.gateforge.model;

import java.time.Instant;
import java.util.List;

public record MigrationPlan(
    String id,
    String gatewayStrategy,
    List<String> sourceProducts,
    List<GeneratedResource> resources,
    String aiAnalysis,
    Instant createdAt,
    String catalogInfoYaml,
    String status,
    String targetClusterId,
    String targetClusterLabel
) {
    public MigrationPlan(String id, String gatewayStrategy, List<String> sourceProducts,
                         List<GeneratedResource> resources, String aiAnalysis, Instant createdAt,
                         String catalogInfoYaml, String status) {
        this(id, gatewayStrategy, sourceProducts, resources, aiAnalysis, createdAt,
             catalogInfoYaml, status, "local", "Local (in-cluster)");
    }

    public record GeneratedResource(String kind, String name, String namespace, String yaml) {}
}

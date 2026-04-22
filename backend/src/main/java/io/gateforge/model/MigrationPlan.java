package io.gateforge.model;

import java.time.Instant;
import java.util.List;

public record MigrationPlan(
    String id,
    String gatewayStrategy,
    List<String> sourceProducts,
    List<GeneratedResource> resources,
    String aiAnalysis,
    Instant createdAt
) {
    public record GeneratedResource(String kind, String name, String namespace, String yaml) {}
}

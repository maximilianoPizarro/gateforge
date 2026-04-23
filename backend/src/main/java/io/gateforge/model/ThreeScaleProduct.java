package io.gateforge.model;

import java.util.List;
import java.util.Map;

public record ThreeScaleProduct(
    String name,
    String namespace,
    String systemName,
    String description,
    String deploymentOption,
    List<MappingRule> mappingRules,
    List<BackendUsage> backendUsages,
    Map<String, Object> authentication,
    String source,
    String backendNamespace,
    String backendServiceName,
    String sourceCluster
) {
    public record MappingRule(String httpMethod, String pattern, String metricRef, int delta) {}
    public record BackendUsage(String backendName, String path) {}
}

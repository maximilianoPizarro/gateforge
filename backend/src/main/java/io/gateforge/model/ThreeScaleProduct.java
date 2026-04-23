package io.gateforge.model;

import java.util.List;
import java.util.Map;

public record ThreeScaleProduct(
    String name,
    String namespace,
    String systemName,
    long serviceId,
    String description,
    String deploymentOption,
    List<MappingRule> mappingRules,
    List<BackendUsage> backendUsages,
    Map<String, Object> authentication,
    String source,
    String backendNamespace,
    String backendServiceName,
    String sourceCluster,
    List<ApplicationPlan> applicationPlans,
    List<Application> applications
) {
    public record MappingRule(String httpMethod, String pattern, String metricRef, int delta) {}
    public record BackendUsage(String backendName, String path) {}
    public record ApplicationPlan(long id, String name, String systemName, String state, List<PlanLimit> limits) {}
    public record PlanLimit(String metricName, String period, long value) {}
    public record Application(long id, String name, String userKey, String planName, String planSystemName, String accountEmail) {}
}

package io.gateforge.model;

import java.util.List;
import java.util.Map;

public record APICastConfig(
    String apiManagerName,
    String namespace,
    String status,
    APICastDeploymentSpec stagingSpec,
    APICastDeploymentSpec productionSpec,
    List<CustomPolicy> customPolicies,
    TLSConfig tls,
    ServiceExposureConfig service,
    OpenTracingConfig openTracing,
    List<ResponseCodeConfig> responseCodes,
    Map<String, String> configurationEnv,
    long productsCount,
    long apiCastPods
) {
    public record APICastDeploymentSpec(int replicas, String cpu, String memory) {}
    public record CustomPolicy(String name, String secretRef, String version, String type) {}
    public record TLSConfig(boolean verify, int verifyDepth, String caSecretRef) {}
    public record ServiceExposureConfig(String type, int port, int targetPort) {}
    public record OpenTracingConfig(boolean enabled, String tracingLibrary, String collectorEndpoint) {}
    public record ResponseCodeConfig(int code, Map<String, String> headers) {}
}

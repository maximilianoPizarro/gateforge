package io.gateforge.service;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.gateforge.model.APICastConfig;
import io.gateforge.model.APICastConfig.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class APICastDiscoveryService {

    private static final Logger LOG = Logger.getLogger(APICastDiscoveryService.class);

    @Inject
    KubernetesClient kubernetesClient;

    @ConfigProperty(name = "gateforge.apicast.discovery", defaultValue = "true")
    boolean discoveryEnabled;

    private static final CustomResourceDefinitionContext APIMANAGER_CTX = new CustomResourceDefinitionContext.Builder()
            .withGroup("apps.3scale.net")
            .withVersion("v1alpha1")
            .withPlural("apimanagers")
            .withScope("Namespaced")
            .build();

    private static final CustomResourceDefinitionContext PRODUCT_CTX = new CustomResourceDefinitionContext.Builder()
            .withGroup("capabilities.3scale.net")
            .withVersion("v1beta1")
            .withPlural("products")
            .withScope("Namespaced")
            .build();

    public List<APICastConfig> discoverAllAPIManagers() {
        if (!discoveryEnabled) {
            LOG.info("APICast discovery is disabled");
            return List.of();
        }
        LOG.info("Scanning all namespaces for APIManagers with APIcast self-managed...");
        try {
            var items = kubernetesClient.genericKubernetesResources(APIMANAGER_CTX)
                    .inAnyNamespace().list().getItems();
            LOG.infof("Found %d total APIManagers in cluster", items.size());

            return items.stream()
                    .filter(this::hasSelfManagedAPICast)
                    .filter(this::isReady)
                    .map(this::analyzeAndEnrich)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            LOG.warnf("Failed to discover APIManagers: %s", e.getMessage());
            return List.of();
        }
    }

    public List<APICastConfig> discoverByNamespace(String namespace) {
        LOG.infof("Scanning namespace '%s' for APIManagers...", namespace);
        try {
            return kubernetesClient.genericKubernetesResources(APIMANAGER_CTX)
                    .inNamespace(namespace).list().getItems().stream()
                    .filter(this::hasSelfManagedAPICast)
                    .filter(this::isReady)
                    .map(this::analyzeAndEnrich)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            LOG.warnf("Failed to discover APIManagers in %s: %s", namespace, e.getMessage());
            return List.of();
        }
    }

    public APICastConfig discoverByName(String name, String namespace) {
        LOG.infof("Looking for APIManager '%s/%s'...", namespace, name);
        try {
            var am = kubernetesClient.genericKubernetesResources(APIMANAGER_CTX)
                    .inNamespace(namespace).withName(name).get();
            if (am == null || !hasSelfManagedAPICast(am)) {
                LOG.warnf("APIManager '%s/%s' not found or not self-managed", namespace, name);
                return null;
            }
            if (!isReady(am)) {
                LOG.warnf("APIManager '%s/%s' is not ready", namespace, name);
                return null;
            }
            return analyzeAndEnrich(am);
        } catch (Exception e) {
            LOG.warnf("Failed to discover APIManager %s/%s: %s", namespace, name, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private boolean hasSelfManagedAPICast(GenericKubernetesResource am) {
        Map<String, Object> spec = (Map<String, Object>) am.getAdditionalProperties().get("spec");
        return spec != null && spec.containsKey("apicast");
    }

    @SuppressWarnings("unchecked")
    private boolean isReady(GenericKubernetesResource am) {
        Map<String, Object> status = (Map<String, Object>) am.getAdditionalProperties().get("status");
        if (status == null) return false;
        Object conditionsObj = status.get("conditions");
        if (conditionsObj instanceof List<?> conditions) {
            return conditions.stream()
                    .filter(c -> c instanceof Map)
                    .map(c -> (Map<String, Object>) c)
                    .anyMatch(c -> "Available".equals(c.get("type")) && "True".equals(c.get("status")));
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private APICastConfig analyzeAndEnrich(GenericKubernetesResource am) {
        String name = am.getMetadata().getName();
        String namespace = am.getMetadata().getNamespace();
        Map<String, Object> spec = (Map<String, Object>) am.getAdditionalProperties().get("spec");
        Map<String, Object> apicastSpec = spec != null ? (Map<String, Object>) spec.get("apicast") : null;

        APICastDeploymentSpec staging = extractDeploymentSpec(apicastSpec, "stagingSpec");
        APICastDeploymentSpec production = extractDeploymentSpec(apicastSpec, "productionSpec");
        List<CustomPolicy> policies = extractCustomPolicies(apicastSpec);
        TLSConfig tls = extractTLSConfig(apicastSpec);
        OpenTracingConfig tracing = extractOpenTracingConfig(apicastSpec);

        long productCount = countProducts(namespace);
        long podCount = countAPICastPods(namespace, name);

        return new APICastConfig(name, namespace, "ready",
                staging, production, policies, tls, null, tracing,
                List.of(), Map.of(), productCount, podCount);
    }

    @SuppressWarnings("unchecked")
    private APICastDeploymentSpec extractDeploymentSpec(Map<String, Object> apicastSpec, String key) {
        if (apicastSpec == null) return null;
        Map<String, Object> depSpec = (Map<String, Object>) apicastSpec.get(key);
        if (depSpec == null) return null;
        int replicas = depSpec.containsKey("replicas") ? ((Number) depSpec.get("replicas")).intValue() : 1;
        String cpu = null;
        String memory = null;
        if (depSpec.containsKey("resources")) {
            Map<String, Object> resources = (Map<String, Object>) depSpec.get("resources");
            Map<String, Object> requests = resources != null ? (Map<String, Object>) resources.get("requests") : null;
            if (requests != null) {
                cpu = requests.containsKey("cpu") ? String.valueOf(requests.get("cpu")) : null;
                memory = requests.containsKey("memory") ? String.valueOf(requests.get("memory")) : null;
            }
        }
        return new APICastDeploymentSpec(replicas, cpu, memory);
    }

    @SuppressWarnings("unchecked")
    private List<CustomPolicy> extractCustomPolicies(Map<String, Object> apicastSpec) {
        if (apicastSpec == null || !apicastSpec.containsKey("customPolicies")) return List.of();
        Object policiesObj = apicastSpec.get("customPolicies");
        if (!(policiesObj instanceof List<?> policies)) return List.of();
        return policies.stream()
                .filter(p -> p instanceof Map)
                .map(p -> {
                    Map<String, Object> pm = (Map<String, Object>) p;
                    return new CustomPolicy(
                            String.valueOf(pm.getOrDefault("name", "unknown")),
                            pm.containsKey("secretRef") ? String.valueOf(((Map<String, Object>) pm.get("secretRef")).getOrDefault("name", "")) : null,
                            String.valueOf(pm.getOrDefault("version", "1.0")),
                            "LUA");
                })
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private TLSConfig extractTLSConfig(Map<String, Object> apicastSpec) {
        if (apicastSpec == null || !apicastSpec.containsKey("tls")) return new TLSConfig(false, 0, null);
        Map<String, Object> tlsSpec = (Map<String, Object>) apicastSpec.get("tls");
        boolean verify = Boolean.parseBoolean(String.valueOf(tlsSpec.getOrDefault("verify", false)));
        int depth = tlsSpec.containsKey("verifyDepth") ? ((Number) tlsSpec.get("verifyDepth")).intValue() : 0;
        return new TLSConfig(verify, depth, null);
    }

    @SuppressWarnings("unchecked")
    private OpenTracingConfig extractOpenTracingConfig(Map<String, Object> apicastSpec) {
        if (apicastSpec == null || !apicastSpec.containsKey("openTracing")) return new OpenTracingConfig(false, null, null);
        Map<String, Object> ot = (Map<String, Object>) apicastSpec.get("openTracing");
        boolean enabled = Boolean.parseBoolean(String.valueOf(ot.getOrDefault("enabled", true)));
        String library = String.valueOf(ot.getOrDefault("tracingLibrary", "jaeger"));
        return new OpenTracingConfig(enabled, library, null);
    }

    private long countProducts(String namespace) {
        try {
            return kubernetesClient.genericKubernetesResources(PRODUCT_CTX)
                    .inNamespace(namespace).list().getItems().size();
        } catch (Exception e) {
            return 0;
        }
    }

    private long countAPICastPods(String namespace, String apiManagerName) {
        try {
            return kubernetesClient.pods().inNamespace(namespace)
                    .withLabel("threescale_component", "apicast")
                    .list().getItems().stream()
                    .filter(pod -> pod.getMetadata().getName().contains(apiManagerName))
                    .count();
        } catch (Exception e) {
            return 0;
        }
    }
}

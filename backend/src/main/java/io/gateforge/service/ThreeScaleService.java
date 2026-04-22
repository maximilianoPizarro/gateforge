package io.gateforge.service;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.gateforge.model.ThreeScaleProduct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@ApplicationScoped
public class ThreeScaleService {

    private static final Logger LOG = Logger.getLogger(ThreeScaleService.class.getName());

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    ThreeScaleAdminApiClient adminApiClient;

    @ConfigProperty(name = "gateforge.threescale.crd-discovery", defaultValue = "true")
    boolean crdDiscoveryEnabled;

    private static final CustomResourceDefinitionContext PRODUCT_CTX = new CustomResourceDefinitionContext.Builder()
            .withGroup("capabilities.3scale.net")
            .withVersion("v1beta1")
            .withPlural("products")
            .withScope("Namespaced")
            .build();

    private static final CustomResourceDefinitionContext BACKEND_CTX = new CustomResourceDefinitionContext.Builder()
            .withGroup("capabilities.3scale.net")
            .withVersion("v1beta1")
            .withPlural("backends")
            .withScope("Namespaced")
            .build();

    /**
     * Returns products from both CRDs and Admin API, deduped by systemName.
     * When both sources provide the same product, enriches the CRD product
     * with Admin API data (mapping rules, backend usages, auth) when the CRD
     * version is missing that information.
     */
    public List<ThreeScaleProduct> listProducts() {
        Map<String, ThreeScaleProduct> crdProducts = new LinkedHashMap<>();
        Map<String, ThreeScaleProduct> apiProducts = new LinkedHashMap<>();

        if (crdDiscoveryEnabled) {
            for (ThreeScaleProduct p : listProductsFromCrds()) {
                crdProducts.put(p.systemName(), p);
            }
        }

        if (adminApiClient.isConfigured()) {
            for (ThreeScaleProduct p : listProductsFromAdminApi()) {
                apiProducts.put(p.systemName(), p);
            }
        }

        Map<String, ThreeScaleProduct> merged = new LinkedHashMap<>();
        for (var entry : crdProducts.entrySet()) {
            ThreeScaleProduct crd = entry.getValue();
            ThreeScaleProduct api = apiProducts.remove(entry.getKey());
            if (api != null) {
                merged.put(entry.getKey(), enrichProduct(crd, api));
            } else {
                merged.put(entry.getKey(), crd);
            }
        }
        merged.putAll(apiProducts);
        return new ArrayList<>(merged.values());
    }

    private ThreeScaleProduct enrichProduct(ThreeScaleProduct crd, ThreeScaleProduct api) {
        List<ThreeScaleProduct.MappingRule> rules = crd.mappingRules().isEmpty()
                ? api.mappingRules() : crd.mappingRules();
        List<ThreeScaleProduct.BackendUsage> usages = crd.backendUsages().isEmpty()
                ? api.backendUsages() : crd.backendUsages();
        Map<String, Object> auth = (crd.authentication() == null || crd.authentication().isEmpty())
                ? api.authentication() : crd.authentication();
        String source = "CRD + Admin API";
        return new ThreeScaleProduct(
                crd.name(), crd.namespace(), crd.systemName(),
                crd.description().isEmpty() ? api.description() : crd.description(),
                crd.deploymentOption(), rules, usages, auth, source
        );
    }

    public List<Map<String, Object>> listBackendsCombined() {
        List<Map<String, Object>> backends = new ArrayList<>();

        if (crdDiscoveryEnabled) {
            try {
                List<GenericKubernetesResource> crdBackends = kubernetesClient
                        .genericKubernetesResources(BACKEND_CTX)
                        .inAnyNamespace().list().getItems();
                for (GenericKubernetesResource r : crdBackends) {
                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put("name", r.getMetadata().getName());
                    b.put("namespace", r.getMetadata().getNamespace());
                    b.put("source", "CRD");
                    b.put("spec", r.getAdditionalProperties().getOrDefault("spec", Collections.emptyMap()));
                    backends.add(b);
                }
            } catch (Exception e) {
                LOG.log(Level.FINE, "CRD backend discovery failed", e);
            }
        }

        if (adminApiClient.isConfigured()) {
            try {
                List<Map<String, Object>> apiBackends = adminApiClient.listBackendApis();
                for (Map<String, Object> ab : apiBackends) {
                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put("name", String.valueOf(ab.getOrDefault("name", "")));
                    b.put("id", ab.get("id"));
                    b.put("systemName", ab.getOrDefault("system_name", ""));
                    b.put("privateEndpoint", ab.getOrDefault("private_endpoint", ""));
                    b.put("description", ab.getOrDefault("description", ""));
                    b.put("source", "Admin API");
                    b.put("createdAt", ab.getOrDefault("created_at", ""));
                    b.put("updatedAt", ab.getOrDefault("updated_at", ""));
                    backends.add(b);
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Admin API backend discovery failed", e);
            }
        }

        return backends;
    }

    public List<GenericKubernetesResource> listBackends() {
        try {
            return kubernetesClient.genericKubernetesResources(BACKEND_CTX)
                    .inAnyNamespace().list().getItems();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public Map<String, Object> getAdminApiStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("configured", adminApiClient.isConfigured());
        status.put("crdDiscoveryEnabled", crdDiscoveryEnabled);

        if (adminApiClient.isConfigured()) {
            try {
                List<Map<String, Object>> services = adminApiClient.listServices();
                List<Map<String, Object>> backendApis = adminApiClient.listBackendApis();
                List<Map<String, Object>> activeDocs = adminApiClient.listActiveDocs();
                status.put("reachable", true);
                status.put("productCount", services.size());
                status.put("backendApiCount", backendApis.size());
                status.put("activeDocsCount", activeDocs.size());
            } catch (Exception e) {
                status.put("reachable", false);
                status.put("error", e.getMessage());
            }
        }

        return status;
    }

    public ThreeScaleProduct getProduct(String name, String namespace) {
        try {
            GenericKubernetesResource resource = kubernetesClient
                    .genericKubernetesResources(PRODUCT_CTX)
                    .inNamespace(namespace).withName(name).get();
            return resource != null ? mapCrdToProduct(resource) : null;
        } catch (Exception e) {
            return null;
        }
    }

    // --- CRD-based discovery ---

    private List<ThreeScaleProduct> listProductsFromCrds() {
        List<ThreeScaleProduct> products = new ArrayList<>();
        try {
            List<GenericKubernetesResource> items = kubernetesClient
                    .genericKubernetesResources(PRODUCT_CTX)
                    .inAnyNamespace().list().getItems();
            for (GenericKubernetesResource item : items) {
                products.add(mapCrdToProduct(item));
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "CRD product discovery failed", e);
        }
        return products;
    }

    @SuppressWarnings("unchecked")
    private ThreeScaleProduct mapCrdToProduct(GenericKubernetesResource resource) {
        Map<String, Object> spec = resource.getAdditionalProperties().containsKey("spec")
                ? (Map<String, Object>) resource.getAdditionalProperties().get("spec")
                : Collections.emptyMap();

        String systemName = String.valueOf(spec.getOrDefault("systemName", resource.getMetadata().getName()));
        String description = String.valueOf(spec.getOrDefault("description", ""));
        String deployment = String.valueOf(spec.getOrDefault("deployment", ""));

        List<ThreeScaleProduct.MappingRule> mappingRules = extractMappingRules(spec);
        List<ThreeScaleProduct.BackendUsage> backendUsages = extractBackendUsages(spec);
        Map<String, Object> auth = spec.containsKey("authentication")
                ? (Map<String, Object>) spec.get("authentication") : Collections.emptyMap();

        return new ThreeScaleProduct(
                resource.getMetadata().getName(),
                resource.getMetadata().getNamespace(),
                systemName, description, deployment,
                mappingRules, backendUsages, auth, "CRD"
        );
    }

    // --- Admin API-based discovery ---

    @SuppressWarnings("unchecked")
    private List<ThreeScaleProduct> listProductsFromAdminApi() {
        List<ThreeScaleProduct> products = new ArrayList<>();
        try {
            List<Map<String, Object>> services = adminApiClient.listServices();
            for (Map<String, Object> svc : services) {
                long serviceId = toLong(svc.get("id"));
                String name = String.valueOf(svc.getOrDefault("name", ""));
                String systemName = String.valueOf(svc.getOrDefault("system_name", name));
                String description = String.valueOf(svc.getOrDefault("description", ""));
                String deployment = String.valueOf(svc.getOrDefault("deployment_option", ""));

                List<ThreeScaleProduct.MappingRule> mappingRules = new ArrayList<>();
                try {
                    List<Map<String, Object>> rules = adminApiClient.listMappingRules(serviceId);
                    for (Map<String, Object> rule : rules) {
                        mappingRules.add(new ThreeScaleProduct.MappingRule(
                                String.valueOf(rule.getOrDefault("http_method", "GET")),
                                String.valueOf(rule.getOrDefault("pattern", "/")),
                                String.valueOf(rule.getOrDefault("metric_id", "")),
                                toInt(rule.getOrDefault("delta", 1))
                        ));
                    }
                } catch (Exception e) {
                    LOG.log(Level.FINE, "Failed to fetch mapping rules for service " + serviceId, e);
                }

                List<ThreeScaleProduct.BackendUsage> backendUsages = new ArrayList<>();
                try {
                    List<Map<String, Object>> usages = adminApiClient.listBackendUsages(serviceId);
                    for (Map<String, Object> usage : usages) {
                        long backendId = toLong(usage.get("backend_id"));
                        String path = String.valueOf(usage.getOrDefault("path", "/"));
                        backendUsages.add(new ThreeScaleProduct.BackendUsage(
                                "backend-" + backendId, path
                        ));
                    }
                } catch (Exception e) {
                    LOG.log(Level.FINE, "Failed to fetch backend usages for service " + serviceId, e);
                }

                Map<String, Object> auth = new LinkedHashMap<>();
                try {
                    Map<String, Object> proxy = adminApiClient.getServiceProxy(serviceId);
                    if (!proxy.isEmpty()) {
                        auth.put("credentials_location", proxy.getOrDefault("credentials_location", ""));
                        auth.put("auth_app_key", proxy.getOrDefault("auth_app_key", ""));
                        auth.put("auth_app_id", proxy.getOrDefault("auth_app_id", ""));
                        auth.put("auth_user_key", proxy.getOrDefault("auth_user_key", ""));
                    }
                } catch (Exception e) {
                    LOG.log(Level.FINE, "Failed to fetch proxy for service " + serviceId, e);
                }

                products.add(new ThreeScaleProduct(
                        name, "admin-api",
                        systemName, description, deployment,
                        mappingRules, backendUsages, auth, "Admin API"
                ));
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Admin API product discovery failed", e);
        }
        return products;
    }

    // --- Helpers ---

    @SuppressWarnings("unchecked")
    private List<ThreeScaleProduct.MappingRule> extractMappingRules(Map<String, Object> spec) {
        Object rules = spec.get("mappingRules");
        if (rules instanceof List<?> list) {
            return list.stream().map(r -> {
                Map<String, Object> rule = (Map<String, Object>) r;
                return new ThreeScaleProduct.MappingRule(
                        String.valueOf(rule.getOrDefault("httpMethod", "GET")),
                        String.valueOf(rule.getOrDefault("pattern", "/")),
                        String.valueOf(rule.getOrDefault("metricMethodRef", "")),
                        toInt(rule.getOrDefault("increment", 1))
                );
            }).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private List<ThreeScaleProduct.BackendUsage> extractBackendUsages(Map<String, Object> spec) {
        Object usages = spec.get("backendUsages");
        if (usages instanceof Map<?, ?> map) {
            return map.entrySet().stream().map(e -> {
                Map<String, Object> val = (Map<String, Object>) e.getValue();
                return new ThreeScaleProduct.BackendUsage(
                        String.valueOf(e.getKey()),
                        String.valueOf(val.getOrDefault("path", "/"))
                );
            }).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    private long toLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(value)); }
        catch (Exception e) { return 0L; }
    }

    private int toInt(Object value) {
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (Exception e) { return 1; }
    }
}

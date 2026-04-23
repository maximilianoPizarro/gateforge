package io.gateforge.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.gateforge.model.ThreeScaleProduct;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@ApplicationScoped
public class ThreeScaleService {

    private static final Logger LOG = Logger.getLogger(ThreeScaleService.class.getName());
    private static final String PRODUCTS_CACHE = "threescale-products";
    private static final String BACKENDS_CACHE = "threescale-backends";
    private static final String CACHE_KEY = "all";

    private final ReentrantLock productsLock = new ReentrantLock();
    private final ReentrantLock backendsLock = new ReentrantLock();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Inject
    RemoteCacheManager cacheManager;

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    ThreeScaleSourceRegistry sourceRegistry;

    @ConfigProperty(name = "gateforge.threescale.crd-discovery", defaultValue = "true")
    boolean crdDiscoveryEnabled;

    @ConfigProperty(name = "gateforge.cache.ttl-seconds", defaultValue = "300")
    long cacheTtlSeconds;

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

    public List<ThreeScaleProduct> listProducts() {
        try {
            RemoteCache<String, String> cache = cacheManager.getCache(PRODUCTS_CACHE);
            if (cache != null) {
                String cached = cache.get(CACHE_KEY);
                if (cached != null) {
                    LOG.info("Products served from Data Grid cache");
                    return objectMapper.readValue(cached, new TypeReference<List<ThreeScaleProduct>>() {});
                }
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Data Grid cache read failed, loading from source", e);
        }

        productsLock.lock();
        try {
            long start = System.currentTimeMillis();
            List<ThreeScaleProduct> result = loadProducts();
            long elapsed = System.currentTimeMillis() - start;
            LOG.info("Loaded %d products in %dms from source".formatted(result.size(), elapsed));

            try {
                RemoteCache<String, String> cache = cacheManager.getCache(PRODUCTS_CACHE);
                if (cache != null) {
                    cache.put(CACHE_KEY, objectMapper.writeValueAsString(result), cacheTtlSeconds, TimeUnit.SECONDS);
                    LOG.info("Products cached in Data Grid (TTL %ds)".formatted(cacheTtlSeconds));
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Data Grid cache write failed", e);
            }

            return result;
        } finally {
            productsLock.unlock();
        }
    }

    private List<ThreeScaleProduct> loadProducts() {
        Map<String, ThreeScaleProduct> crdProducts = new LinkedHashMap<>();
        Map<String, ThreeScaleProduct> apiProducts = new LinkedHashMap<>();

        if (crdDiscoveryEnabled) {
            for (ThreeScaleProduct p : listProductsFromCrds()) {
                crdProducts.put(p.systemName(), p);
            }
        }

        for (ThreeScaleAdminApiClient client : sourceRegistry.getAllClients()) {
            if (!client.isConfigured()) continue;
            for (ThreeScaleProduct p : listProductsFromAdminApi(client)) {
                String key = client.getSourceId() + ":" + p.systemName();
                apiProducts.put(key, p);
            }
        }

        Map<String, ThreeScaleProduct> merged = new LinkedHashMap<>();
        for (var entry : crdProducts.entrySet()) {
            ThreeScaleProduct crd = entry.getValue();
            ThreeScaleProduct api = null;
            for (var apiEntry : apiProducts.entrySet()) {
                if (apiEntry.getKey().endsWith(":" + entry.getKey())) {
                    api = apiEntry.getValue();
                    apiProducts.remove(apiEntry.getKey());
                    break;
                }
            }
            if (api != null) {
                merged.put(entry.getKey(), enrichProduct(crd, api));
            } else {
                merged.put(entry.getKey(), crd);
            }
        }
        for (var apiEntry : apiProducts.entrySet()) {
            String key = apiEntry.getKey();
            String sysName = key.contains(":") ? key.substring(key.indexOf(':') + 1) : key;
            merged.put(key, apiEntry.getValue());
        }

        Map<String, String[]> backendEndpoints = resolveBackendEndpointMap();
        List<ThreeScaleProduct> result = new ArrayList<>();
        for (ThreeScaleProduct p : merged.values()) {
            result.add(resolveBackendInfo(p, backendEndpoints));
        }
        return result;
    }

    private ThreeScaleProduct enrichProduct(ThreeScaleProduct crd, ThreeScaleProduct api) {
        List<ThreeScaleProduct.MappingRule> rules = crd.mappingRules().isEmpty()
                ? api.mappingRules() : crd.mappingRules();
        List<ThreeScaleProduct.BackendUsage> usages = crd.backendUsages().isEmpty()
                ? api.backendUsages() : crd.backendUsages();
        Map<String, Object> auth = (crd.authentication() == null || crd.authentication().isEmpty())
                ? api.authentication() : crd.authentication();
        String source = "CRD + Admin API (" + api.sourceCluster() + ")";
        return new ThreeScaleProduct(
                crd.name(), crd.namespace(), crd.systemName(),
                crd.description().isEmpty() ? api.description() : crd.description(),
                crd.deploymentOption(), rules, usages, auth, source,
                null, null, api.sourceCluster()
        );
    }

    public List<Map<String, Object>> listBackendsCombined() {
        try {
            RemoteCache<String, String> cache = cacheManager.getCache(BACKENDS_CACHE);
            if (cache != null) {
                String cached = cache.get(CACHE_KEY);
                if (cached != null) {
                    LOG.info("Backends served from Data Grid cache");
                    return objectMapper.readValue(cached, new TypeReference<List<Map<String, Object>>>() {});
                }
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Data Grid cache read failed for backends, loading from source", e);
        }

        backendsLock.lock();
        try {
            long start = System.currentTimeMillis();
            List<Map<String, Object>> result = loadBackendsCombined();
            long elapsed = System.currentTimeMillis() - start;
            LOG.info("Loaded %d backends in %dms from source".formatted(result.size(), elapsed));

            try {
                RemoteCache<String, String> cache = cacheManager.getCache(BACKENDS_CACHE);
                if (cache != null) {
                    cache.put(CACHE_KEY, objectMapper.writeValueAsString(result), cacheTtlSeconds, TimeUnit.SECONDS);
                    LOG.info("Backends cached in Data Grid (TTL %ds)".formatted(cacheTtlSeconds));
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Data Grid cache write failed for backends", e);
            }

            return result;
        } finally {
            backendsLock.unlock();
        }
    }

    private List<Map<String, Object>> loadBackendsCombined() {
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
                    b.put("sourceCluster", "local");
                    b.put("spec", r.getAdditionalProperties().getOrDefault("spec", Collections.emptyMap()));
                    backends.add(b);
                }
            } catch (Exception e) {
                LOG.log(Level.FINE, "CRD backend discovery failed", e);
            }
        }

        for (ThreeScaleAdminApiClient client : sourceRegistry.getAllClients()) {
            if (!client.isConfigured()) continue;
            try {
                List<Map<String, Object>> apiBackends = client.listBackendApis();
                for (Map<String, Object> ab : apiBackends) {
                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put("name", String.valueOf(ab.getOrDefault("name", "")));
                    b.put("id", ab.get("id"));
                    b.put("systemName", ab.getOrDefault("system_name", ""));
                    b.put("privateEndpoint", ab.getOrDefault("private_endpoint", ""));
                    b.put("description", ab.getOrDefault("description", ""));
                    b.put("source", "Admin API");
                    b.put("sourceCluster", client.getSourceId());
                    b.put("createdAt", ab.getOrDefault("created_at", ""));
                    b.put("updatedAt", ab.getOrDefault("updated_at", ""));
                    backends.add(b);
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Admin API backend discovery failed for source " + client.getSourceId(), e);
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
        status.put("crdDiscoveryEnabled", crdDiscoveryEnabled);

        List<Map<String, Object>> sourceStatuses = new ArrayList<>();
        for (ThreeScaleAdminApiClient client : sourceRegistry.getAllClients()) {
            sourceStatuses.add(sourceRegistry.getSourceStatus(client.getSourceId()));
        }
        status.put("sources", sourceStatuses);
        status.put("configured", sourceRegistry.hasConfiguredClients());

        if (sourceRegistry.hasConfiguredClients()) {
            ThreeScaleAdminApiClient client = sourceRegistry.getDefaultClient();
            if (client != null && client.isConfigured()) {
                try {
                    List<Map<String, Object>> services = client.listServices();
                    List<Map<String, Object>> backendApis = client.listBackendApis();
                    List<Map<String, Object>> activeDocs = client.listActiveDocs();
                    status.put("reachable", true);
                    status.put("productCount", services.size());
                    status.put("backendApiCount", backendApis.size());
                    status.put("activeDocsCount", activeDocs.size());
                } catch (Exception e) {
                    status.put("reachable", false);
                    status.put("error", e.getMessage());
                }
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
                mappingRules, backendUsages, auth, "CRD",
                null, null, "local"
        );
    }

    @SuppressWarnings("unchecked")
    private List<ThreeScaleProduct> listProductsFromAdminApi(ThreeScaleAdminApiClient client) {
        List<ThreeScaleProduct> products = new ArrayList<>();
        try {
            List<Map<String, Object>> services = client.listServices();
            for (Map<String, Object> svc : services) {
                long serviceId = toLong(svc.get("id"));
                String name = String.valueOf(svc.getOrDefault("name", ""));
                String systemName = String.valueOf(svc.getOrDefault("system_name", name));
                String description = String.valueOf(svc.getOrDefault("description", ""));
                String deployment = String.valueOf(svc.getOrDefault("deployment_option", ""));

                List<ThreeScaleProduct.MappingRule> mappingRules = new ArrayList<>();
                try {
                    List<Map<String, Object>> rules = client.listMappingRules(serviceId);
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
                    List<Map<String, Object>> usages = client.listBackendUsages(serviceId);
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
                    Map<String, Object> proxy = client.getServiceProxy(serviceId);
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
                        mappingRules, backendUsages, auth,
                        "Admin API (" + client.getSourceId() + ")",
                        null, null, client.getSourceId()
                ));
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Admin API product discovery failed for source " + client.getSourceId(), e);
        }
        return products;
    }

    private Map<String, String[]> resolveBackendEndpointMap() {
        Map<String, String[]> map = new HashMap<>();
        for (ThreeScaleAdminApiClient client : sourceRegistry.getAllClients()) {
            if (!client.isConfigured()) continue;
            try {
                List<Map<String, Object>> backends = client.listBackendApis();
                for (Map<String, Object> b : backends) {
                    String ep = String.valueOf(b.getOrDefault("private_endpoint", ""));
                    if (ep.isBlank()) continue;

                    String sysName = String.valueOf(b.getOrDefault("system_name", ""));
                    String bName = String.valueOf(b.getOrDefault("name", ""));
                    Object idObj = b.get("id");
                    long id = idObj instanceof Number n ? n.longValue() : 0L;

                    String[] svcInfo = extractSvcInfo(ep);

                    if (!sysName.isBlank()) map.put(sysName, svcInfo);
                    if (!bName.isBlank()) map.put(bName, svcInfo);
                    if (id > 0) map.put("backend-" + id, svcInfo);
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to resolve backend endpoints for source " + client.getSourceId(), e);
            }
        }
        return map;
    }

    private ThreeScaleProduct resolveBackendInfo(ThreeScaleProduct p, Map<String, String[]> endpoints) {
        for (ThreeScaleProduct.BackendUsage usage : p.backendUsages()) {
            String[] info = endpoints.get(usage.backendName());
            if (info != null) {
                return new ThreeScaleProduct(
                        p.name(), p.namespace(), p.systemName(),
                        p.description(), p.deploymentOption(),
                        p.mappingRules(), p.backendUsages(), p.authentication(),
                        p.source(), info[1], info[0], p.sourceCluster()
                );
            }
        }
        return p;
    }

    private String[] extractSvcInfo(String endpoint) {
        try {
            java.net.URI uri = java.net.URI.create(endpoint);
            String host = uri.getHost();
            if (host != null) {
                String[] parts = host.split("\\.");
                String svcName = parts.length > 0 ? parts[0] : host;
                String ns = parts.length > 1 ? parts[1] : "";
                return new String[]{svcName, ns};
            }
        } catch (Exception ignored) {}
        return new String[]{"", ""};
    }

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

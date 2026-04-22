package io.gateforge.service;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.gateforge.model.ThreeScaleProduct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class ThreeScaleService {

    @Inject
    KubernetesClient kubernetesClient;

    @ConfigProperty(name = "gateforge.threescale.admin-url", defaultValue = "")
    String adminUrl;

    @ConfigProperty(name = "gateforge.threescale.access-token", defaultValue = "")
    String accessToken;

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
        List<ThreeScaleProduct> products = new ArrayList<>();
        try {
            List<GenericKubernetesResource> items = kubernetesClient
                    .genericKubernetesResources(PRODUCT_CTX)
                    .inAnyNamespace().list().getItems();

            for (GenericKubernetesResource item : items) {
                products.add(mapToProduct(item));
            }
        } catch (Exception e) {
            // CRD might not exist in cluster; return empty
        }
        return products;
    }

    public List<GenericKubernetesResource> listBackends() {
        try {
            return kubernetesClient.genericKubernetesResources(BACKEND_CTX)
                    .inAnyNamespace().list().getItems();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public ThreeScaleProduct getProduct(String name, String namespace) {
        try {
            GenericKubernetesResource resource = kubernetesClient
                    .genericKubernetesResources(PRODUCT_CTX)
                    .inNamespace(namespace).withName(name).get();
            return resource != null ? mapToProduct(resource) : null;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private ThreeScaleProduct mapToProduct(GenericKubernetesResource resource) {
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
                mappingRules, backendUsages, auth
        );
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
                        ((Number) rule.getOrDefault("increment", 1)).intValue()
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
}

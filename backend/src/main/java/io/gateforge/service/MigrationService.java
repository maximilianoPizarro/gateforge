package io.gateforge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gateforge.model.MigrationPlan;
import io.gateforge.model.ThreeScaleProduct;
import io.gateforge.model.AuditEntry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

@ApplicationScoped
public class MigrationService {

    private static final Logger LOG = Logger.getLogger(MigrationService.class);

    @Inject
    ThreeScaleService threeScaleService;

    @Inject
    KuadrantCtlService kuadrantCtlService;

    @Inject
    ObjectMapper objectMapper;

    private final List<AuditEntry> auditLog = new CopyOnWriteArrayList<>();
    private final Map<String, MigrationPlan> plans = new LinkedHashMap<>();

    public MigrationPlan analyze(String gatewayStrategy, List<String> productNames) {
        List<ThreeScaleProduct> products = threeScaleService.listProducts().stream()
                .filter(p -> productNames.isEmpty() || productNames.contains(p.name()))
                .toList();

        List<MigrationPlan.GeneratedResource> resources = new ArrayList<>();

        for (ThreeScaleProduct product : products) {
            String oasSpec = buildOpenApiFromProduct(product);
            LOG.debugf("Generated OpenAPI for %s: %s", product.systemName(), oasSpec);

            String httpRouteYaml = kuadrantCtlService.generateHttpRoute(oasSpec);
            if (!httpRouteYaml.startsWith("ERROR")) {
                resources.add(new MigrationPlan.GeneratedResource(
                        "HTTPRoute", product.systemName() + "-route",
                        product.namespace(), httpRouteYaml));
            } else {
                LOG.warnf("HTTPRoute generation failed for %s: %s", product.systemName(), httpRouteYaml);
            }

            String authPolicyYaml = kuadrantCtlService.generateAuthPolicy(oasSpec);
            if (!authPolicyYaml.startsWith("ERROR")) {
                resources.add(new MigrationPlan.GeneratedResource(
                        "AuthPolicy", product.systemName() + "-auth",
                        product.namespace(), authPolicyYaml));
            }

            String rlpYaml = kuadrantCtlService.generateRateLimitPolicy(oasSpec);
            if (!rlpYaml.startsWith("ERROR")) {
                resources.add(new MigrationPlan.GeneratedResource(
                        "RateLimitPolicy", product.systemName() + "-ratelimit",
                        product.namespace(), rlpYaml));
            }
        }

        if ("dual".equals(gatewayStrategy)) {
            resources.add(0, buildGatewayResource("gateforge-internal", "internal"));
            resources.add(1, buildGatewayResource("gateforge-external", "external"));
        } else if ("dedicated".equals(gatewayStrategy)) {
            for (ThreeScaleProduct p : products) {
                resources.add(0, buildGatewayResource(p.systemName() + "-gw", p.systemName()));
            }
        } else {
            resources.add(0, buildGatewayResource("gateforge-shared", "shared"));
        }

        String planId = UUID.randomUUID().toString().substring(0, 8);
        MigrationPlan plan = new MigrationPlan(
                planId, gatewayStrategy,
                products.stream().map(ThreeScaleProduct::name).toList(),
                resources, "AI analysis pending", Instant.now()
        );
        plans.put(planId, plan);
        return plan;
    }

    public List<AuditEntry> getAuditLog() {
        return Collections.unmodifiableList(auditLog);
    }

    public MigrationPlan getPlan(String planId) {
        return plans.get(planId);
    }

    public List<MigrationPlan> listPlans() {
        return new ArrayList<>(plans.values());
    }

    public void addAuditEntry(AuditEntry entry) {
        auditLog.add(entry);
    }

    private MigrationPlan.GeneratedResource buildGatewayResource(String name, String label) {
        String yaml = """
                apiVersion: gateway.networking.k8s.io/v1
                kind: Gateway
                metadata:
                  name: %s
                  labels:
                    gateforge.io/type: %s
                spec:
                  gatewayClassName: istio
                  listeners:
                    - name: http
                      port: 80
                      protocol: HTTP
                      allowedRoutes:
                        namespaces:
                          from: All
                """.formatted(name, label);
        return new MigrationPlan.GeneratedResource("Gateway", name, "kuadrant-system", yaml);
    }

    private String buildOpenApiFromProduct(ThreeScaleProduct product) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("openapi", "3.0.3");
        spec.put("info", Map.of("title", product.systemName(), "version", "1.0"));

        Map<String, Object> paths = new LinkedHashMap<>();
        for (ThreeScaleProduct.MappingRule rule : product.mappingRules()) {
            String path = sanitizePath(rule.pattern());
            @SuppressWarnings("unchecked")
            Map<String, Object> pathItem = (Map<String, Object>)
                    paths.computeIfAbsent(path, k -> new LinkedHashMap<>());
            String metricRef = rule.metricRef() != null && !rule.metricRef().isBlank()
                    ? rule.metricRef() : "op";
            String opId = metricRef + "_" + rule.httpMethod().toLowerCase();
            pathItem.put(rule.httpMethod().toLowerCase(), Map.of(
                    "operationId", opId,
                    "responses", Map.of("200", Map.of("description", "OK"))
            ));
        }

        if (paths.isEmpty()) {
            Map<String, Object> rootOp = new LinkedHashMap<>();
            rootOp.put("get", Map.of(
                    "operationId", product.systemName() + "_root",
                    "responses", Map.of("200", Map.of("description", "OK"))
            ));
            paths.put("/", rootOp);
        }

        spec.put("paths", paths);

        try {
            return objectMapper.writeValueAsString(spec);
        } catch (JsonProcessingException e) {
            LOG.errorf("Failed to serialize OpenAPI spec for %s: %s", product.systemName(), e.getMessage());
            return "{\"openapi\":\"3.0.3\",\"info\":{\"title\":\"%s\",\"version\":\"1.0\"},\"paths\":{\"/\":{\"get\":{\"operationId\":\"fallback\",\"responses\":{\"200\":{\"description\":\"OK\"}}}}}}"
                    .formatted(product.systemName());
        }
    }

    private String sanitizePath(String pattern) {
        if (pattern == null || pattern.isBlank()) return "/";
        String p = pattern.replaceAll("\\$$", "");
        if (!p.startsWith("/")) p = "/" + p;
        if (p.isEmpty()) p = "/";
        return p;
    }
}

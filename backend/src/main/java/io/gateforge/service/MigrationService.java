package io.gateforge.service;

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

    private final List<AuditEntry> auditLog = new CopyOnWriteArrayList<>();
    private final Map<String, MigrationPlan> plans = new LinkedHashMap<>();

    public MigrationPlan analyze(String gatewayStrategy, List<String> productNames) {
        List<ThreeScaleProduct> products = threeScaleService.listProducts().stream()
                .filter(p -> productNames.isEmpty() || productNames.contains(p.name()))
                .toList();

        List<MigrationPlan.GeneratedResource> resources = new ArrayList<>();

        for (ThreeScaleProduct product : products) {
            String oasSpec = buildOpenApiFromProduct(product);

            String httpRouteYaml = kuadrantCtlService.generateHttpRoute(oasSpec);
            if (!httpRouteYaml.startsWith("ERROR")) {
                resources.add(new MigrationPlan.GeneratedResource(
                        "HTTPRoute", product.systemName() + "-route",
                        product.namespace(), httpRouteYaml));
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
        StringBuilder paths = new StringBuilder();
        for (ThreeScaleProduct.MappingRule rule : product.mappingRules()) {
            String method = rule.httpMethod().toLowerCase();
            paths.append("  ").append(rule.pattern()).append(":\n");
            paths.append("    ").append(method).append(":\n");
            paths.append("      operationId: ").append(rule.metricRef()).append("\n");
            paths.append("      responses:\n");
            paths.append("        '200':\n");
            paths.append("          description: OK\n");
        }
        return """
                openapi: "3.0.3"
                info:
                  title: %s
                  version: "1.0"
                paths:
                %s""".formatted(product.systemName(), paths);
    }
}

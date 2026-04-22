package io.gateforge.service;

import io.gateforge.model.MigrationPlan;
import io.gateforge.model.ThreeScaleProduct;
import io.gateforge.model.AuditEntry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@ApplicationScoped
public class MigrationService {

    private static final Logger LOG = Logger.getLogger(MigrationService.class);

    @Inject
    ThreeScaleService threeScaleService;

    @ConfigProperty(name = "gateforge.connectivity-link.gateway-class-name", defaultValue = "istio")
    String gatewayClassName;

    @ConfigProperty(name = "gateforge.connectivity-link.target-namespace", defaultValue = "kuadrant-system")
    String gatewayNamespace;

    private final List<AuditEntry> auditLog = new CopyOnWriteArrayList<>();
    private final Map<String, MigrationPlan> plans = new LinkedHashMap<>();

    public MigrationPlan analyze(String gatewayStrategy, List<String> productNames) {
        List<ThreeScaleProduct> products = threeScaleService.listProducts().stream()
                .filter(p -> productNames.isEmpty() || productNames.contains(p.name()))
                .toList();

        List<MigrationPlan.GeneratedResource> resources = new ArrayList<>();
        String gatewayName;

        if ("dual".equals(gatewayStrategy)) {
            resources.add(buildGatewayResource("gateforge-internal", "internal"));
            resources.add(buildGatewayResource("gateforge-external", "external"));
            gatewayName = "gateforge-external";
        } else if ("dedicated".equals(gatewayStrategy)) {
            gatewayName = null;
        } else {
            resources.add(buildGatewayResource("gateforge-shared", "shared"));
            gatewayName = "gateforge-shared";
        }

        for (ThreeScaleProduct product : products) {
            String sysName = product.systemName();
            String ns = product.namespace() != null && !product.namespace().isBlank()
                    ? product.namespace() : gatewayNamespace;
            String routeName = sysName + "-route";

            if ("dedicated".equals(gatewayStrategy)) {
                String gwName = sysName + "-gw";
                resources.add(buildGatewayResource(gwName, sysName));
                gatewayName = gwName;
            }

            resources.add(buildHttpRoute(routeName, ns, gatewayName, product));
            resources.add(buildAuthPolicy(sysName + "-auth", ns, routeName, product));
            resources.add(buildRateLimitPolicy(sysName + "-ratelimit", ns, routeName, product));
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
                  namespace: %s
                  labels:
                    gateforge.io/type: %s
                    app.kubernetes.io/managed-by: gateforge
                spec:
                  gatewayClassName: %s
                  listeners:
                    - name: http
                      port: 80
                      protocol: HTTP
                      allowedRoutes:
                        namespaces:
                          from: All
                """.formatted(name, gatewayNamespace, label, gatewayClassName);
        return new MigrationPlan.GeneratedResource("Gateway", name, gatewayNamespace, yaml);
    }

    private MigrationPlan.GeneratedResource buildHttpRoute(
            String name, String namespace, String gatewayName, ThreeScaleProduct product) {

        StringBuilder rules = new StringBuilder();

        if (product.mappingRules().isEmpty()) {
            rules.append("""
                        - matches:
                            - path:
                                type: PathPrefix
                                value: /
                          backendRefs:
                            - name: %s
                              port: 8080
                      """.formatted(product.systemName()));
        } else {
            Map<String, List<ThreeScaleProduct.MappingRule>> byPath = product.mappingRules().stream()
                    .collect(Collectors.groupingBy(
                            r -> sanitizePath(r.pattern()), LinkedHashMap::new, Collectors.toList()));

            for (var entry : byPath.entrySet()) {
                String path = entry.getKey();
                List<ThreeScaleProduct.MappingRule> pathRules = entry.getValue();
                rules.append("        - matches:\n");
                for (ThreeScaleProduct.MappingRule rule : pathRules) {
                    rules.append("            - path:\n");
                    rules.append("                type: Exact\n");
                    rules.append("                value: ").append(path).append("\n");
                    rules.append("              method: ").append(rule.httpMethod().toUpperCase()).append("\n");
                }
                rules.append("          backendRefs:\n");
                rules.append("            - name: ").append(product.systemName()).append("\n");
                rules.append("              port: 8080\n");
            }
        }

        String yaml = """
                apiVersion: gateway.networking.k8s.io/v1
                kind: HTTPRoute
                metadata:
                  name: %s
                  namespace: %s
                  labels:
                    app.kubernetes.io/managed-by: gateforge
                    gateforge.io/product: %s
                spec:
                  parentRefs:
                    - name: %s
                      namespace: %s
                  rules:
                %s""".formatted(name, namespace, product.systemName(),
                gatewayName, gatewayNamespace, rules.toString());

        return new MigrationPlan.GeneratedResource("HTTPRoute", name, namespace, yaml);
    }

    private MigrationPlan.GeneratedResource buildAuthPolicy(
            String name, String namespace, String routeName, ThreeScaleProduct product) {

        String authSection;
        Map<String, Object> auth = product.authentication();
        if (auth != null && !auth.isEmpty()) {
            String authType = String.valueOf(auth.getOrDefault("type", "apiKey"));
            if ("oidc".equalsIgnoreCase(authType) || "openid_connect".equalsIgnoreCase(authType)) {
                String issuer = String.valueOf(auth.getOrDefault("issuerEndpoint",
                        "https://sso.example.com/realms/api"));
                authSection = """
                          authorization:
                            oidc:
                              when:
                                - selector: request.path
                                  operator: matches
                                  value: ".*"
                          authentication:
                            "oidc-auth":
                              jwt:
                                issuerUrl: %s
                      """.formatted(issuer);
            } else {
                authSection = """
                          authentication:
                            "apikey-auth":
                              apiKey:
                                selector:
                                  matchLabels:
                                    app: %s
                                allNamespaces: false
                      """.formatted(product.systemName());
            }
        } else {
            authSection = """
                          authentication:
                            "apikey-auth":
                              apiKey:
                                selector:
                                  matchLabels:
                                    app: %s
                                allNamespaces: false
                      """.formatted(product.systemName());
        }

        String yaml = """
                apiVersion: kuadrant.io/v1
                kind: AuthPolicy
                metadata:
                  name: %s
                  namespace: %s
                  labels:
                    app.kubernetes.io/managed-by: gateforge
                    gateforge.io/product: %s
                spec:
                  targetRef:
                    group: gateway.networking.k8s.io
                    kind: HTTPRoute
                    name: %s
                  rules:
                %s""".formatted(name, namespace, product.systemName(), routeName, authSection);

        return new MigrationPlan.GeneratedResource("AuthPolicy", name, namespace, yaml);
    }

    private MigrationPlan.GeneratedResource buildRateLimitPolicy(
            String name, String namespace, String routeName, ThreeScaleProduct product) {

        String yaml = """
                apiVersion: kuadrant.io/v1
                kind: RateLimitPolicy
                metadata:
                  name: %s
                  namespace: %s
                  labels:
                    app.kubernetes.io/managed-by: gateforge
                    gateforge.io/product: %s
                spec:
                  targetRef:
                    group: gateway.networking.k8s.io
                    kind: HTTPRoute
                    name: %s
                  limits:
                    "global":
                      rates:
                        - limit: 100
                          window: 60s
                """.formatted(name, namespace, product.systemName(), routeName);

        return new MigrationPlan.GeneratedResource("RateLimitPolicy", name, namespace, yaml);
    }

    private String sanitizePath(String pattern) {
        if (pattern == null || pattern.isBlank()) return "/";
        String p = pattern.replaceAll("\\$$", "");
        if (!p.startsWith("/")) p = "/" + p;
        if (p.isEmpty()) p = "/";
        return p;
    }
}

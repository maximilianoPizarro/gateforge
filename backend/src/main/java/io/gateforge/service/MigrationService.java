package io.gateforge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gateforge.model.MigrationPlan;
import io.gateforge.model.ThreeScaleProduct;
import io.gateforge.model.AuditEntry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@ApplicationScoped
public class MigrationService {

    private static final Logger LOG = Logger.getLogger(MigrationService.class);

    @Inject
    ThreeScaleService threeScaleService;

    @Inject
    ThreeScaleAdminApiClient adminApiClient;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "gateforge.connectivity-link.gateway-class-name", defaultValue = "istio")
    String gatewayClassName;

    @ConfigProperty(name = "gateforge.connectivity-link.target-namespace", defaultValue = "kuadrant-system")
    String gatewayNamespace;

    private final List<AuditEntry> auditLog = new CopyOnWriteArrayList<>();
    private final Map<String, MigrationPlan> plans = new LinkedHashMap<>();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public MigrationPlan analyze(String gatewayStrategy, List<String> productNames) {
        List<ThreeScaleProduct> products = threeScaleService.listProducts().stream()
                .filter(p -> productNames.isEmpty() || productNames.contains(p.name()))
                .toList();

        BackendIndex backendEndpoints = resolveBackendEndpoints();

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

            List<ThreeScaleProduct.MappingRule> effectiveRules = product.mappingRules();
            String backendSvcName = sysName;
            if (effectiveRules.isEmpty()) {
                var discovered = discoverPathsFromBackends(product, backendEndpoints);
                effectiveRules = discovered.rules;
                if (discovered.serviceName != null) {
                    backendSvcName = discovered.serviceName;
                }
            }

            resources.add(buildHttpRoute(routeName, ns, gatewayName, product, effectiveRules, backendSvcName));
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
            String name, String namespace, String gatewayName,
            ThreeScaleProduct product, List<ThreeScaleProduct.MappingRule> effectiveRules,
            String backendSvcName) {

        StringBuilder rules = new StringBuilder();

        if (effectiveRules.isEmpty()) {
            rules.append("""
                        - matches:
                            - path:
                                type: PathPrefix
                                value: /
                          backendRefs:
                            - name: %s
                              port: 8080
                      """.formatted(backendSvcName));
        } else {
            Map<String, List<ThreeScaleProduct.MappingRule>> byPath = effectiveRules.stream()
                    .collect(Collectors.groupingBy(
                            r -> sanitizePath(r.pattern()), LinkedHashMap::new, Collectors.toList()));

            for (var entry : byPath.entrySet()) {
                String path = entry.getKey();
                List<ThreeScaleProduct.MappingRule> pathRules = entry.getValue();
                boolean hasParam = path.contains("{");
                String pathType = hasParam ? "PathPrefix" : "Exact";
                String pathValue = hasParam ? path.replaceAll("/\\{[^}]+}", "") : path;
                if (pathValue.isEmpty()) pathValue = "/";

                rules.append("        - matches:\n");
                for (ThreeScaleProduct.MappingRule rule : pathRules) {
                    rules.append("            - path:\n");
                    rules.append("                type: ").append(pathType).append("\n");
                    rules.append("                value: ").append(pathValue).append("\n");
                    rules.append("              method: ").append(rule.httpMethod().toUpperCase()).append("\n");
                }
                rules.append("          backendRefs:\n");
                rules.append("            - name: ").append(backendSvcName).append("\n");
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

    private record BackendIndex(Map<Long, String> byId, Map<String, String> byName) {}
    private record DiscoveredPaths(List<ThreeScaleProduct.MappingRule> rules, String serviceName) {}

    private DiscoveredPaths discoverPathsFromBackends(
            ThreeScaleProduct product, BackendIndex backends) {

        for (ThreeScaleProduct.BackendUsage usage : product.backendUsages()) {
            String endpoint = null;

            long backendId = extractBackendId(usage.backendName());
            if (backendId > 0) {
                endpoint = backends.byId.get(backendId);
            }
            if (endpoint == null) {
                endpoint = backends.byName.get(usage.backendName());
            }
            if (endpoint == null || endpoint.isBlank()) continue;

            String svcName = extractServiceName(endpoint);
            List<ThreeScaleProduct.MappingRule> rules = fetchOpenApiPaths(endpoint);
            if (!rules.isEmpty()) {
                LOG.infof("Discovered %d paths from backend %s for product %s",
                        rules.size(), endpoint, product.systemName());
                return new DiscoveredPaths(rules, svcName);
            }
        }
        return new DiscoveredPaths(List.of(), null);
    }

    private BackendIndex resolveBackendEndpoints() {
        Map<Long, String> byId = new HashMap<>();
        Map<String, String> byName = new HashMap<>();
        if (!adminApiClient.isConfigured()) return new BackendIndex(byId, byName);
        try {
            List<Map<String, Object>> backends = adminApiClient.listBackendApis();
            for (Map<String, Object> b : backends) {
                String ep = String.valueOf(b.getOrDefault("private_endpoint", ""));
                if (ep.isBlank()) continue;

                Object idObj = b.get("id");
                long id = idObj instanceof Number n ? n.longValue() : 0L;
                if (id > 0) byId.put(id, ep);

                String sysName = String.valueOf(b.getOrDefault("system_name", ""));
                if (!sysName.isBlank()) byName.put(sysName, ep);

                String name = String.valueOf(b.getOrDefault("name", ""));
                if (!name.isBlank()) byName.put(name, ep);
            }
        } catch (Exception e) {
            LOG.warn("Failed to resolve backend endpoints", e);
        }
        return new BackendIndex(byId, byName);
    }

    @SuppressWarnings("unchecked")
    private List<ThreeScaleProduct.MappingRule> fetchOpenApiPaths(String baseUrl) {
        for (String suffix : List.of("/q/openapi", "/openapi.json", "/openapi", "/swagger.json")) {
            try {
                String url = baseUrl.endsWith("/")
                        ? baseUrl.substring(0, baseUrl.length() - 1) + suffix
                        : baseUrl + suffix;

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(5))
                        .header("Accept", "application/json")
                        .GET().build();

                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) continue;

                String body = resp.body().trim();
                JsonNode root;
                if (body.startsWith("{")) {
                    root = objectMapper.readTree(body);
                } else {
                    org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
                    Map<String, Object> map = yaml.load(body);
                    root = objectMapper.valueToTree(map);
                }

                JsonNode paths = root.get("paths");
                if (paths == null || !paths.isObject()) continue;

                List<ThreeScaleProduct.MappingRule> rules = new ArrayList<>();
                var fields = paths.fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    String path = entry.getKey();
                    if (path.equals("/")) continue;
                    JsonNode methods = entry.getValue();
                    var methodFields = methods.fields();
                    while (methodFields.hasNext()) {
                        var mEntry = methodFields.next();
                        String method = mEntry.getKey().toUpperCase();
                        if (Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS").contains(method)) {
                            String opId = "";
                            JsonNode opNode = mEntry.getValue();
                            if (opNode.has("operationId")) {
                                opId = opNode.get("operationId").asText();
                            }
                            rules.add(new ThreeScaleProduct.MappingRule(method, path, opId, 1));
                        }
                    }
                }
                if (!rules.isEmpty()) return rules;

            } catch (Exception e) {
                LOG.debugf("OpenAPI fetch failed for %s: %s", baseUrl + suffix, e.getMessage());
            }
        }
        return List.of();
    }

    private String extractServiceName(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            String host = uri.getHost();
            if (host != null && host.contains(".")) {
                return host.substring(0, host.indexOf('.'));
            }
            return host != null ? host : "";
        } catch (Exception e) {
            return "";
        }
    }

    private long extractBackendId(String backendName) {
        if (backendName != null && backendName.startsWith("backend-")) {
            try {
                return Long.parseLong(backendName.substring("backend-".length()));
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
        return 0L;
    }

    private String sanitizePath(String pattern) {
        if (pattern == null || pattern.isBlank()) return "/";
        String p = pattern.replaceAll("\\$$", "");
        if (!p.startsWith("/")) p = "/" + p;
        if (p.isEmpty()) p = "/";
        return p;
    }
}

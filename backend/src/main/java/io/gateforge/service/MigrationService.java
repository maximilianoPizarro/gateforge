package io.gateforge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gateforge.entity.AuditEntryEntity;
import io.gateforge.entity.GeneratedResourceEntity;
import io.gateforge.entity.MigrationPlanEntity;
import io.gateforge.model.MigrationPlan;
import io.gateforge.model.ThreeScaleProduct;
import io.gateforge.model.AuditEntry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class MigrationService {

    private static final Logger LOG = Logger.getLogger(MigrationService.class);

    @Inject
    ThreeScaleService threeScaleService;

    @Inject
    ThreeScaleSourceRegistry sourceRegistry;

    @Inject
    ClusterRegistry clusterRegistry;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "gateforge.connectivity-link.gateway-class-name", defaultValue = "istio")
    String gatewayClassName;

    @ConfigProperty(name = "gateforge.connectivity-link.target-namespace", defaultValue = "kuadrant-system")
    String gatewayNamespace;

    @ConfigProperty(name = "gateforge.cluster-domain", defaultValue = "apps.cluster.example.com")
    String clusterDomain;

    @ConfigProperty(name = "gateforge.developer-hub.enabled", defaultValue = "false")
    boolean developerHubEnabled;

    @ConfigProperty(name = "gateforge.developer-hub.url", defaultValue = "none")
    String developerHubUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @Transactional
    public MigrationPlan analyze(String gatewayStrategy, List<String> productNames, String targetClusterId) {
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
            String routeName = sysName + "-route";

            if ("dedicated".equals(gatewayStrategy)) {
                String gwName = sysName + "-gw";
                resources.add(buildGatewayResource(gwName, sysName));
                gatewayName = gwName;
            }

            List<ThreeScaleProduct.MappingRule> effectiveRules = product.mappingRules();
            String backendSvcName = sysName;
            String ns = null;

            var discovered = discoverPathsFromBackends(product, backendEndpoints);
            if (!discovered.rules.isEmpty()) {
                effectiveRules = discovered.rules;
            }
            if (discovered.serviceName != null) {
                backendSvcName = discovered.serviceName;
            }
            if (discovered.namespace != null && !discovered.namespace.isBlank()) {
                ns = discovered.namespace;
            }

            if (ns == null || ns.isBlank()) {
                ns = product.backendNamespace();
            }
            if (ns == null || ns.isBlank()) {
                ns = gatewayNamespace;
            }

            resources.add(buildHttpRoute(routeName, ns, gatewayName, product, effectiveRules, backendSvcName));
            resources.add(buildAuthPolicy(sysName + "-auth", ns, routeName, product));
            resources.add(buildRateLimitPolicy(sysName + "-ratelimit", ns, routeName, product));
            resources.add(buildOpenShiftRoute(sysName, gatewayName));
        }

        String catalogInfo = developerHubEnabled ? buildCatalogInfo(products, gatewayStrategy) : null;

        String planId = UUID.randomUUID().toString().substring(0, 8);
        String effectiveClusterId = targetClusterId != null ? targetClusterId : "local";
        io.gateforge.model.TargetCluster cluster = clusterRegistry.getCluster(effectiveClusterId);
        String clusterLabel = cluster != null ? cluster.label() : "Local (in-cluster)";

        MigrationPlan plan = new MigrationPlan(
                planId, gatewayStrategy,
                products.stream().map(ThreeScaleProduct::name).toList(),
                resources, "AI analysis pending", Instant.now(),
                catalogInfo, "ACTIVE", effectiveClusterId, clusterLabel
        );

        persistPlan(plan);
        return plan;
    }

    public List<AuditEntry> getAuditLog() {
        List<AuditEntryEntity> entities = AuditEntryEntity.listAll(io.quarkus.panache.common.Sort.by("timestamp").descending());
        return entities.stream().map(this::toAuditEntry).collect(Collectors.toList());
    }

    public MigrationPlan getPlan(String planId) {
        MigrationPlanEntity entity = MigrationPlanEntity.findById(planId);
        return entity != null ? toPlan(entity) : null;
    }

    public List<MigrationPlan> listPlans() {
        List<MigrationPlanEntity> entities = MigrationPlanEntity.listAll(io.quarkus.panache.common.Sort.by("createdAt").descending());
        return entities.stream().map(this::toPlan).collect(Collectors.toList());
    }

    @Transactional
    public void addAuditEntry(AuditEntry entry) {
        AuditEntryEntity e = new AuditEntryEntity();
        e.id = entry.id();
        e.timestamp = entry.timestamp();
        e.action = entry.action();
        e.resourceKind = entry.resourceKind();
        e.resourceName = entry.resourceName();
        e.namespace = entry.namespace();
        e.yamlBefore = entry.yamlBefore();
        e.yamlAfter = entry.yamlAfter();
        e.performedBy = entry.performedBy();
        e.targetClusterId = entry.targetClusterId();
        e.persist();
    }

    @Transactional
    public void updatePlanStatus(String planId, String status) {
        MigrationPlanEntity entity = MigrationPlanEntity.findById(planId);
        if (entity != null) {
            entity.status = status;
            entity.persist();
        }
    }

    @Transactional
    void persistPlan(MigrationPlan plan) {
        MigrationPlanEntity entity = new MigrationPlanEntity();
        entity.id = plan.id();
        entity.gatewayStrategy = plan.gatewayStrategy();
        try {
            entity.sourceProductsJson = objectMapper.writeValueAsString(plan.sourceProducts());
        } catch (Exception e) {
            entity.sourceProductsJson = "[]";
        }
        entity.aiAnalysis = plan.aiAnalysis();
        entity.createdAt = plan.createdAt();
        entity.catalogInfoYaml = plan.catalogInfoYaml();
        entity.status = plan.status();
        entity.targetClusterId = plan.targetClusterId();
        entity.targetClusterLabel = plan.targetClusterLabel();

        List<GeneratedResourceEntity> resourceEntities = new ArrayList<>();
        for (MigrationPlan.GeneratedResource r : plan.resources()) {
            GeneratedResourceEntity re = new GeneratedResourceEntity();
            re.kind = r.kind();
            re.name = r.name();
            re.namespace = r.namespace();
            re.yaml = r.yaml();
            re.plan = entity;
            resourceEntities.add(re);
        }
        entity.resources = resourceEntities;
        entity.persist();
    }

    private MigrationPlan toPlan(MigrationPlanEntity e) {
        List<String> products;
        try {
            products = objectMapper.readValue(e.sourceProductsJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception ex) {
            products = List.of();
        }
        List<MigrationPlan.GeneratedResource> resources = e.resources != null
                ? e.resources.stream()
                    .map(r -> new MigrationPlan.GeneratedResource(r.kind, r.name, r.namespace, r.yaml))
                    .collect(Collectors.toList())
                : List.of();
        return new MigrationPlan(
                e.id, e.gatewayStrategy, products, resources,
                e.aiAnalysis, e.createdAt, e.catalogInfoYaml, e.status,
                e.targetClusterId, e.targetClusterLabel
        );
    }

    private AuditEntry toAuditEntry(AuditEntryEntity e) {
        return new AuditEntry(
                e.id, e.timestamp, e.action, e.resourceKind, e.resourceName,
                e.namespace, e.yamlBefore, e.yamlAfter, e.performedBy, e.targetClusterId
        );
    }

    public List<Map<String, String>> generateTestCommands(String planId) {
        MigrationPlan plan = getPlan(planId);
        if (plan == null) return List.of();

        List<Map<String, String>> commands = new ArrayList<>();
        String gatewayHost = null;

        for (MigrationPlan.GeneratedResource res : plan.resources()) {
            if ("Route".equals(res.kind()) && res.yaml().contains("host:")) {
                String yaml = res.yaml();
                int idx = yaml.indexOf("host:");
                if (idx >= 0) {
                    String rest = yaml.substring(idx + 5).trim();
                    String host = rest.split("\\s")[0].trim();
                    gatewayHost = host;

                    commands.add(Map.of(
                            "label", "Test " + res.name() + " (no auth — expect 401/403)",
                            "command", "curl -sk https://" + host + "/",
                            "type", "no-auth"
                    ));
                    commands.add(Map.of(
                            "label", "Test " + res.name() + " with API Key",
                            "command", "curl -sk -H \"X-API-Key: YOUR_API_KEY\" https://" + host + "/api/v1/",
                            "type", "api-key"
                    ));
                    commands.add(Map.of(
                            "label", "Swagger UI",
                            "command", "https://" + host + "/q/swagger-ui",
                            "type", "url"
                    ));
                    commands.add(Map.of(
                            "label", "OpenAPI Spec",
                            "command", "https://" + host + "/q/openapi",
                            "type", "url"
                    ));
                }
            }
        }
        return commands;
    }

    private String buildCatalogInfo(List<ThreeScaleProduct> products, String strategy) {
        StringBuilder sb = new StringBuilder();
        String gatewayName = "dedicated".equals(strategy) ? null :
                "dual".equals(strategy) ? "gateforge-external" : "gateforge-shared";

        for (int i = 0; i < products.size(); i++) {
            ThreeScaleProduct p = products.get(i);
            String sysName = p.systemName();
            String ns = p.backendNamespace() != null && !p.backendNamespace().isBlank()
                    ? p.backendNamespace() : gatewayNamespace;
            String routeName = sysName + "-route";
            String gw = "dedicated".equals(strategy) ? sysName + "-gw" : gatewayName;

            if (i > 0) sb.append("\n---\n");

            sb.append("""
                    apiVersion: backstage.io/v1alpha1
                    kind: API
                    metadata:
                      name: %s
                      namespace: default
                      description: "%s — migrated from 3scale to Connectivity Link by GateForge"
                      annotations:
                        kuadrant.io/namespace: %s
                        kuadrant.io/httproute: %s
                        backstage.io/kubernetes-namespace: %s
                      tags:
                        - connectivity-link
                        - kuadrant
                        - gateforge-migrated
                        - gateway-api
                    spec:
                      type: openapi
                      lifecycle: production
                      owner: platform-engineering
                      definition: |
                        openapi: "3.0.3"
                        info:
                          title: %s
                          description: "API migrated from 3scale by GateForge"
                          version: "1.0.0"
                        servers:
                          - url: https://%s
                            description: "Connectivity Link Gateway"
                        paths:
                          /:
                            get:
                              summary: Root endpoint
                              responses:
                                '200':
                                  description: OK
                    """.formatted(
                    sysName, p.description() != null ? p.description().replace("\"", "'") : sysName,
                    ns, routeName, ns,
                    p.name(),
                    gw != null ? gw + "." + clusterDomain : sysName + "." + clusterDomain
            ));
        }
        return sb.toString();
    }

    private MigrationPlan.GeneratedResource buildGatewayResource(String name, String label) {
        String yaml = """
                apiVersion: gateway.networking.k8s.io/v1
                kind: Gateway
                metadata:
                  name: %s
                  namespace: %s
                  annotations:
                    networking.istio.io/service-type: ClusterIP
                  labels:
                    gateforge.io/type: %s
                    app.kubernetes.io/managed-by: gateforge
                spec:
                  gatewayClassName: %s
                  listeners:
                    - name: http
                      port: 80
                      protocol: HTTP
                      hostname: "*.%s"
                      allowedRoutes:
                        namespaces:
                          from: All
                """.formatted(name, gatewayNamespace, label, gatewayClassName, clusterDomain);
        return new MigrationPlan.GeneratedResource("Gateway", name, gatewayNamespace, yaml);
    }

    private MigrationPlan.GeneratedResource buildOpenShiftRoute(String sysName, String gatewayName) {
        String hostname = sysName + "." + clusterDomain;
        String svcName = gatewayName + "-istio";
        String yaml = """
                apiVersion: route.openshift.io/v1
                kind: Route
                metadata:
                  name: %s
                  namespace: %s
                  labels:
                    app.kubernetes.io/managed-by: gateforge
                spec:
                  host: %s
                  to:
                    kind: Service
                    name: %s
                    weight: 100
                  port:
                    targetPort: http
                  tls:
                    termination: edge
                    insecureEdgeTerminationPolicy: Redirect
                """.formatted(sysName, gatewayNamespace, hostname, svcName);
        return new MigrationPlan.GeneratedResource("Route", sysName, gatewayNamespace, yaml);
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
            Set<String> prefixes = new LinkedHashSet<>();
            for (ThreeScaleProduct.MappingRule r : effectiveRules) {
                String p = sanitizePath(r.pattern());
                if (p.contains("{")) p = p.replaceAll("/\\{[^}]+}.*", "");
                String[] segments = p.split("/");
                String prefix = segments.length >= 2 ? "/" + segments[1] : "/";
                prefixes.add(prefix);
            }

            if (prefixes.size() > 16) {
                prefixes = Set.of("/");
            }

            for (String prefix : prefixes) {
                rules.append("        - matches:\n");
                rules.append("            - path:\n");
                rules.append("                type: PathPrefix\n");
                rules.append("                value: ").append(prefix).append("\n");
                rules.append("          backendRefs:\n");
                rules.append("            - name: ").append(backendSvcName).append("\n");
                rules.append("              port: 8080\n");
            }
        }

        String hostname = product.systemName() + "." + clusterDomain;

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
                  hostnames:
                    - %s
                  parentRefs:
                    - name: %s
                      namespace: %s
                  rules:
                %s""".formatted(name, namespace, product.systemName(),
                hostname, gatewayName, gatewayNamespace, rules.toString());

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
    private record DiscoveredPaths(List<ThreeScaleProduct.MappingRule> rules, String serviceName, String namespace) {}

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
            String svcNs = extractServiceNamespace(endpoint);
            List<ThreeScaleProduct.MappingRule> rules = fetchOpenApiPaths(endpoint);
            if (!rules.isEmpty()) {
                LOG.infof("Discovered %d paths from backend %s (ns=%s) for product %s",
                        rules.size(), endpoint, svcNs, product.systemName());
                return new DiscoveredPaths(rules, svcName, svcNs);
            }
        }
        return new DiscoveredPaths(List.of(), null, null);
    }

    private BackendIndex resolveBackendEndpoints() {
        Map<Long, String> byId = new HashMap<>();
        Map<String, String> byName = new HashMap<>();
        if (!sourceRegistry.hasConfiguredClients()) return new BackendIndex(byId, byName);

        for (ThreeScaleAdminApiClient client : sourceRegistry.getAllClients()) {
            if (!client.isConfigured()) continue;
            try {
                List<Map<String, Object>> backends = client.listBackendApis();
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
                LOG.warnf("Failed to resolve backend endpoints for source %s", client.getSourceId());
            }
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

    private String extractServiceNamespace(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            String host = uri.getHost();
            if (host != null) {
                String[] parts = host.split("\\.");
                if (parts.length >= 2) {
                    return parts[1];
                }
            }
        } catch (Exception e) {
            LOG.debugf("Failed to extract namespace from %s", endpoint);
        }
        return null;
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

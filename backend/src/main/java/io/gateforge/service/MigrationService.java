package io.gateforge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gateforge.ai.MigrationAgent;
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

    @Inject
    KuadrantCtlService kuadrantCtlService;

    @Inject
    MigrationAgent migrationAgent;

    @Inject
    GateForgeMetrics metrics;

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

    @ConfigProperty(name = "gateforge.developer-hub.component-suffix", defaultValue = "-product")
    String componentSuffix;

    @ConfigProperty(name = "gateforge.observability.enabled", defaultValue = "false")
    boolean observabilityEnabled;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private record ResolvedBackend(String svcName, String svcNamespace, String path) {}

    private record ProductContext(
            String oasContent, String backendSvcName, String namespace,
            List<ThreeScaleProduct.MappingRule> effectiveRules, boolean hasRealOas,
            List<ResolvedBackend> resolvedBackends) {

        ProductContext(String oasContent, String backendSvcName, String namespace,
                       List<ThreeScaleProduct.MappingRule> effectiveRules, boolean hasRealOas) {
            this(oasContent, backendSvcName, namespace, effectiveRules, hasRealOas, List.of());
        }
    }

    public MigrationPlan analyze(String gatewayStrategy, List<String> productNames, String targetClusterId) {
        io.micrometer.core.instrument.Timer.Sample timerSample = metrics.startMigrationTimer();
        List<String> consolidationWarnings = new ArrayList<>();
        List<ThreeScaleProduct> products = threeScaleService.listProducts().stream()
                .filter(p -> productNames.isEmpty() || productNames.contains(p.name()))
                .toList();
        metrics.setProductsDiscovered(products.size());

        BackendIndex backendEndpoints = resolveBackendEndpoints();

        List<MigrationPlan.GeneratedResource> resources = new ArrayList<>();
        Map<String, String> oasCache = new LinkedHashMap<>();
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

            ProductContext ctx = resolveProductContext(product, backendEndpoints);

            String ns = ctx.namespace;
            if (ns == null || ns.isBlank()) ns = product.backendNamespace();
            if (ns == null || ns.isBlank()) ns = gatewayNamespace;

            String hostname = sysName + "." + clusterDomain;
            String effectiveOas = ctx.oasContent;

            if (effectiveOas == null) {
                effectiveOas = buildOpenApiFromMappingRules(product, ctx.effectiveRules, hostname);
            }

            oasCache.put(sysName, effectiveOas);

            boolean usedKuadrantctl = false;
            boolean multiBackend = ctx.resolvedBackends.size() > 1;

            if (!multiBackend && effectiveOas != null) {
                try {
                    String httpRouteOut = kuadrantCtlService.generateHttpRoute(effectiveOas);
                    if (httpRouteOut != null && !httpRouteOut.startsWith("ERROR") && httpRouteOut.contains("kind")) {
                        String patchedHttp = patchKuadrantctlOutput(httpRouteOut, "HTTPRoute", routeName,
                                ns, gatewayName, gatewayNamespace, sysName, hostname);
                        patchedHttp = consolidateHttpRouteRules(patchedHttp, ctx.backendSvcName, consolidationWarnings);
                        resources.add(new MigrationPlan.GeneratedResource("HTTPRoute", routeName, ns, patchedHttp));
                        usedKuadrantctl = true;
                        LOG.infof("kuadrantctl generated HTTPRoute for %s:\n%s", sysName, patchedHttp);
                    }
                } catch (Exception e) {
                    LOG.warnf("kuadrantctl HTTPRoute failed for %s: %s", sysName, e.getMessage());
                }
            } else if (multiBackend) {
                LOG.infof("Product %s has %d backends, skipping kuadrantctl for multi-backend HTTPRoute",
                        sysName, ctx.resolvedBackends.size());
            }

            if (!usedKuadrantctl) {
                resources.add(buildHttpRoute(routeName, ns, gatewayName, product, ctx.effectiveRules,
                        ctx.backendSvcName, ctx.resolvedBackends));
            }

            resources.add(buildAuthPolicy(sysName + "-auth", ns, routeName, product));
            resources.add(buildRateLimitPolicy(sysName + "-ratelimit", ns, routeName, product));

            if (product.applicationPlans() != null && !product.applicationPlans().isEmpty()) {
                resources.add(buildPlanPolicy(sysName + "-plans", ns, routeName, product));
            }

            resources.add(buildApiProduct(sysName, ns, routeName, product));

            if (product.applications() != null && !product.applications().isEmpty()) {
                List<MigrationPlan.GeneratedResource> apiKeys = buildApiKeys(sysName, ns, product);
                resources.addAll(apiKeys);
            }

            if (observabilityEnabled) {
                resources.add(buildTelemetryPolicy(sysName + "-telemetry", ns, routeName, product));
            }

            resources.add(buildOpenShiftRoute(sysName, gatewayName));
        }

        String catalogInfo = developerHubEnabled ? buildCatalogInfo(products, gatewayStrategy, oasCache, resources) : null;

        String planId = UUID.randomUUID().toString().substring(0, 8);
        String effectiveClusterId = targetClusterId != null ? targetClusterId : "local";
        io.gateforge.model.TargetCluster cluster = clusterRegistry.getCluster(effectiveClusterId);
        String clusterLabel = cluster != null ? cluster.label() : "Local (in-cluster)";

        String aiAnalysis = runAiVerification(products, resources);

        MigrationPlan plan = new MigrationPlan(
                planId, gatewayStrategy,
                products.stream().map(ThreeScaleProduct::systemName).toList(),
                resources, aiAnalysis, Instant.now(),
                catalogInfo, "ACTIVE", effectiveClusterId, clusterLabel, consolidationWarnings
        );

        persistPlan(plan);
        metrics.stopMigrationTimer(timerSample);
        for (ThreeScaleProduct p : products) {
            metrics.recordMigration(p.systemName(), "analyzed");
        }
        return plan;
    }

    private ProductContext resolveProductContext(ThreeScaleProduct product, BackendIndex backends) {
        List<ResolvedBackend> resolvedBackends = new ArrayList<>();
        String primarySvcName = null;
        String primarySvcNs = null;
        String primaryOas = null;
        List<ThreeScaleProduct.MappingRule> primaryRules = null;
        boolean hasRealOas = false;

        for (ThreeScaleProduct.BackendUsage usage : product.backendUsages()) {
            String endpoint = null;
            long backendId = extractBackendId(usage.backendName());
            if (backendId > 0) endpoint = backends.byId.get(backendId);
            if (endpoint == null) endpoint = backends.byName.get(usage.backendName());
            if (endpoint == null || endpoint.isBlank()) continue;

            String svcName = extractServiceName(endpoint);
            String svcNs = extractServiceNamespace(endpoint);
            String path = usage.path() != null && !usage.path().isBlank() ? usage.path() : "/";
            resolvedBackends.add(new ResolvedBackend(svcName, svcNs, path));

            if (primarySvcName == null) {
                primarySvcName = svcName;
                primarySvcNs = svcNs;

                String fullOas = fetchFullOpenApiSpec(endpoint);
                if (fullOas != null) {
                    LOG.infof("Fetched real OpenAPI spec from %s for product %s", endpoint, product.systemName());
                    primaryOas = fullOas;
                    hasRealOas = true;
                } else {
                    List<ThreeScaleProduct.MappingRule> rules = fetchOpenApiPaths(endpoint);
                    if (!rules.isEmpty()) {
                        primaryRules = rules;
                    }
                }
            }
        }

        if (primarySvcName == null) {
            return new ProductContext(null, product.systemName(), null, product.mappingRules(), false, List.of());
        }

        List<ThreeScaleProduct.MappingRule> effectiveRules = primaryRules != null ? primaryRules : product.mappingRules();
        return new ProductContext(primaryOas, primarySvcName, primarySvcNs, effectiveRules, hasRealOas, resolvedBackends);
    }

    private String fetchFullOpenApiSpec(String baseUrl) {
        for (String suffix : List.of("/q/openapi", "/openapi.json", "/openapi", "/swagger.json",
                "/q/openapi?format=json", "/v3/api-docs")) {
            try {
                String url = baseUrl.endsWith("/")
                        ? baseUrl.substring(0, baseUrl.length() - 1) + suffix
                        : baseUrl + suffix;

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(5))
                        .header("Accept", "application/json, application/yaml")
                        .GET().build();

                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) continue;

                String body = resp.body().trim();
                if (body.isEmpty()) continue;

                JsonNode root;
                if (body.startsWith("{")) {
                    root = objectMapper.readTree(body);
                } else {
                    org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = yaml.load(body);
                    root = objectMapper.valueToTree(map);
                }

                if (root.has("paths") && root.get("paths").isObject() && root.get("paths").size() > 0) {
                    return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
                }
            } catch (Exception e) {
                LOG.debugf("OpenAPI fetch failed for %s%s: %s", baseUrl, suffix, e.getMessage());
            }
        }
        return null;
    }

    private String buildOpenApiFromMappingRules(ThreeScaleProduct product,
            List<ThreeScaleProduct.MappingRule> rules, String hostname) {
        if (rules == null || rules.isEmpty()) {
            return buildMinimalOpenApi(product, hostname);
        }

        Map<String, Map<String, Object>> paths = new LinkedHashMap<>();

        for (ThreeScaleProduct.MappingRule rule : rules) {
            String path = sanitizePath(rule.pattern());
            if (path.contains("{")) path = path.replaceAll("/\\{[^}]+}", "/{id}");

            paths.computeIfAbsent(path, k -> new LinkedHashMap<>());
            Map<String, Object> method = new LinkedHashMap<>();
            method.put("summary", rule.metricRef() != null && !rule.metricRef().isBlank()
                    ? rule.metricRef() : rule.httpMethod() + " " + path);
            method.put("operationId", rule.httpMethod().toLowerCase() + path.replaceAll("[^a-zA-Z0-9]", "_"));

            boolean isCollection = "GET".equalsIgnoreCase(rule.httpMethod()) && !path.contains("{id}");
            Map<String, Object> responses = buildEnrichedResponses(path, product.name(), isCollection);
            method.put("responses", responses);
            paths.get(path).put(rule.httpMethod().toLowerCase(), method);
        }

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("openapi", "3.0.3");
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("title", product.name());
        info.put("description", product.description() != null ? product.description() : "Migrated from 3scale by GateForge");
        info.put("version", "1.0.0");
        spec.put("info", info);
        spec.put("servers", List.of(Map.of("url", "https://" + hostname)));
        spec.put("paths", paths);

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(spec);
        } catch (Exception e) {
            LOG.warnf("Failed to serialize synthetic OAS for %s", product.systemName());
            return null;
        }
    }

    private String buildMinimalOpenApi(ThreeScaleProduct product, String hostname) {
        Map<String, Object> okContent = new LinkedHashMap<>();
        okContent.put("schema", Map.of("type", "object", "properties", Map.of(
                "status", Map.of("type", "string"),
                "service", Map.of("type", "string"),
                "timestamp", Map.of("type", "string", "format", "date-time"))));
        okContent.put("example", Map.of("status", "ok", "service", product.name(), "timestamp", "2026-04-22T10:30:00Z"));

        Map<String, Object> okResp = new LinkedHashMap<>();
        okResp.put("description", "OK");
        okResp.put("content", Map.of("application/json", okContent));

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("openapi", "3.0.3");
        spec.put("info", Map.of("title", product.name(), "version", "1.0.0"));
        spec.put("servers", List.of(Map.of("url", "https://" + hostname)));
        spec.put("paths", Map.of("/", Map.of("get", Map.of(
                "summary", "Root endpoint",
                "operationId", "getRoot",
                "responses", Map.of("200", okResp,
                        "401", UNAUTHORIZED_RESPONSE)))));
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(spec);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> buildEnrichedResponses(String path, String productName, boolean isCollection) {
        Map<String, Object> example = resolveExamplePayload(path, productName);
        Object body = isCollection ? List.of(example) : example;

        Map<String, Object> okContent = new LinkedHashMap<>();
        okContent.put("schema", Map.of("type", isCollection ? "array" : "object"));
        okContent.put("example", body);

        Map<String, Object> okResp = new LinkedHashMap<>();
        okResp.put("description", "Success");
        okResp.put("content", Map.of("application/json", okContent));

        Map<String, Object> responses = new LinkedHashMap<>();
        responses.put("200", okResp);
        responses.put("401", UNAUTHORIZED_RESPONSE);
        responses.put("404", NOT_FOUND_RESPONSE);
        return responses;
    }

    private static final Map<String, Object> UNAUTHORIZED_RESPONSE = Map.of(
            "description", "Unauthorized",
            "content", Map.of("application/json", Map.of(
                    "example", Map.of("error", "unauthorized", "message", "API key is missing or invalid"))));

    private static final Map<String, Object> NOT_FOUND_RESPONSE = Map.of(
            "description", "Not Found",
            "content", Map.of("application/json", Map.of(
                    "example", Map.of("error", "not_found", "message", "Resource not found"))));

    private Map<String, Object> resolveExamplePayload(String path, String productName) {
        String lp = path.toLowerCase();
        if (lp.contains("account")) {
            return Map.of("accountId", "ACC-001", "holder", "John Doe", "balance", 1500.00,
                    "currency", "USD", "status", "active");
        }
        if (lp.contains("card") || lp.contains("card-issuing")) {
            return Map.of("cardId", "CARD-9876", "last4", "4242", "type", "virtual",
                    "status", "active", "expiresAt", "2028-12");
        }
        if (lp.contains("transaction") || lp.contains("payment")) {
            return Map.of("transactionId", "TXN-5432", "amount", 99.99, "currency", "USD",
                    "status", "completed", "createdAt", "2026-04-22T10:30:00Z");
        }
        if (lp.contains("wallet") || lp.contains("nfl-wallet")) {
            return Map.of("walletId", "W-001", "owner", "user1", "balance", 250.00,
                    "currency", "USD");
        }
        if (lp.contains("user") || lp.contains("customer")) {
            return Map.of("userId", "USR-001", "name", "Jane Smith", "email", "jane@example.com",
                    "status", "active");
        }
        if (lp.contains("product") || lp.contains("catalog")) {
            return Map.of("productId", "PROD-001", "name", "Premium Plan", "price", 29.99,
                    "currency", "USD", "available", true);
        }
        if (lp.contains("order")) {
            return Map.of("orderId", "ORD-001", "total", 149.99, "currency", "USD",
                    "status", "confirmed", "createdAt", "2026-04-22T10:30:00Z");
        }
        return Map.of("id", "resource-001", "name", productName, "status", "ok",
                "timestamp", "2026-04-22T10:30:00Z");
    }

    private String patchKuadrantctlOutput(String yaml, String kind, String name,
            String namespace, String gatewayName, String gwNamespace,
            String productSysName, String hostname) {
        yaml = yaml.replace("\r\n", "\n").replace("\r", "\n");
        yaml = yaml.replaceAll("(?m)^\\s*creationTimestamp:\\s*null\\s*\\n", "");
        yaml = yaml.replaceAll("(?m)^\\s*status:\\s*\\n(\\s+\\S+.*\\n)*", "");

        String metadataBlock = "metadata:\n"
                + "  name: " + name + "\n"
                + "  namespace: " + namespace + "\n"
                + "  labels:\n"
                + "    app.kubernetes.io/managed-by: gateforge\n"
                + "    \"gateforge.io/product\": \"" + productSysName + "\"";
        yaml = yaml.replaceFirst("(?m)^metadata:(\\s*\\n(\\s+.*\\n)*?)(?=^\\S)", metadataBlock + "\n");
        if (!yaml.contains("  name: " + name)) {
            yaml = yaml.replaceFirst("(?m)^metadata:", metadataBlock);
        }

        if ("HTTPRoute".equals(kind) && gatewayName != null && !yaml.contains("parentRefs")) {
            String specBlock = "spec:\n"
                    + "  hostnames:\n"
                    + "    - " + hostname + "\n"
                    + "  parentRefs:\n"
                    + "    - name: " + gatewayName + "\n"
                    + "      namespace: " + gwNamespace;
            yaml = yaml.replaceFirst("(?m)^spec:", specBlock);
        }

        return yaml;
    }

    private String consolidateHttpRouteRules(String yaml, String backendSvcName, List<String> warnings) {
        long ruleCount = yaml.lines()
                .filter(line -> line.trim().startsWith("- matches:"))
                .count();

        if (ruleCount <= 16) return yaml;

        LOG.infof("HTTPRoute has %d rules (max 16), consolidating to prefix-based rules", ruleCount);

        int rulesStart = yaml.indexOf("\n  rules:\n");
        if (rulesStart < 0) return yaml;

        String rulesSection = yaml.substring(rulesStart);
        Set<String> prefixes = new LinkedHashSet<>();

        for (String line : rulesSection.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("value:")) {
                String val = trimmed.substring(6).trim().replaceAll("^[\"']|[\"']$", "");
                if (val.startsWith("/")) {
                    String[] segments = val.split("/", 3);
                    if (segments.length > 1 && !segments[1].isEmpty()) {
                        prefixes.add("/" + segments[1]);
                    } else {
                        prefixes.add("/");
                    }
                }
            }
        }

        if (prefixes.isEmpty()) prefixes.add("/");
        if (prefixes.size() > 16) {
            prefixes = new LinkedHashSet<>();
            prefixes.add("/");
        }

        warnings.add("HTTPRoute has " + ruleCount + " rules (max 16), consolidated to " + prefixes.size()
                + " prefix-based rules: " + prefixes);

        LOG.infof("Consolidated %d rules into %d prefix-based rules: %s", ruleCount, prefixes.size(), prefixes);

        StringBuilder rules = new StringBuilder();
        for (String prefix : prefixes) {
            rules.append("    - matches:\n");
            rules.append("        - path:\n");
            rules.append("            type: PathPrefix\n");
            rules.append("            value: ").append(prefix).append("\n");
            rules.append("      backendRefs:\n");
            rules.append("        - name: ").append(backendSvcName).append("\n");
            rules.append("          port: 8080\n");
        }

        return yaml.substring(0, rulesStart) + "\n  rules:\n" + rules;
    }

    private String runAiVerification(List<ThreeScaleProduct> products, List<MigrationPlan.GeneratedResource> resources) {
        try {
            StringBuilder prompt = new StringBuilder();
            prompt.append("Review this GateForge migration plan. Verify correctness and provide a brief analysis.\n\n");
            prompt.append("Products being migrated: ");
            prompt.append(products.stream().map(ThreeScaleProduct::name).collect(Collectors.joining(", ")));
            prompt.append("\n\nGenerated resources summary:\n");

            for (MigrationPlan.GeneratedResource r : resources) {
                prompt.append("- ").append(r.kind()).append(": ").append(r.name())
                        .append(" (ns: ").append(r.namespace()).append(")\n");
            }

            long httpRouteCount = resources.stream().filter(r -> "HTTPRoute".equals(r.kind())).count();
            long authCount = resources.stream().filter(r -> "AuthPolicy".equals(r.kind())).count();
            long rlCount = resources.stream().filter(r -> "RateLimitPolicy".equals(r.kind())).count();

            prompt.append("\nSample HTTPRoute YAML:\n```yaml\n");
            resources.stream().filter(r -> "HTTPRoute".equals(r.kind())).findFirst()
                    .ifPresent(r -> prompt.append(r.yaml()));
            prompt.append("\n```\n");

            prompt.append("\nProvide: 1) Correctness assessment, 2) Potential issues, 3) Recommendations. Keep it concise (max 200 words).");

            String analysis = migrationAgent.chat(prompt.toString());
            if (analysis != null) {
                analysis = analysis.replaceAll("(?s)<think>.*?</think>\\s*", "").trim();
                int closeIdx = analysis.indexOf("</think>");
                if (closeIdx >= 0) analysis = analysis.substring(closeIdx + "</think>".length()).trim();
            }
            return analysis != null ? analysis : "AI verification unavailable";
        } catch (Exception e) {
            LOG.warnf("AI verification failed: %s", e.getMessage());
            return "AI verification skipped: " + e.getMessage();
        }
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
        return entities.stream().map(this::toPlanSummary).collect(Collectors.toList());
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
                e.targetClusterId, e.targetClusterLabel, List.of()
        );
    }

    /**
     * List view / hub overview: metadata only (avoids loading large generated YAML blobs).
     */
    private MigrationPlan toPlanSummary(MigrationPlanEntity e) {
        List<String> products;
        try {
            products = objectMapper.readValue(e.sourceProductsJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception ex) {
            products = List.of();
        }
        return new MigrationPlan(
                e.id, e.gatewayStrategy, products,
                List.of(),
                e.aiAnalysis, e.createdAt, e.catalogInfoYaml, e.status,
                e.targetClusterId, e.targetClusterLabel, List.of()
        );
    }

    private AuditEntry toAuditEntry(AuditEntryEntity e) {
        return new AuditEntry(
                e.id, e.timestamp, e.action, e.resourceKind, e.resourceName,
                e.namespace, e.yamlBefore, e.yamlAfter, e.performedBy, e.targetClusterId
        );
    }

    public String getCatalogInfoForProduct(String planId, String productName) {
        MigrationPlan plan = getPlan(planId);
        if (plan == null) return null;

        String sysName = sanitizeName(productName);
        String componentName = sysName + componentSuffix;
        String catalogYaml = plan.catalogInfoYaml();

        if (catalogYaml != null && !catalogYaml.isBlank()) {
            StringBuilder result = new StringBuilder();
            String[] docs = catalogYaml.split("(?m)^---\\s*$");
            for (String doc : docs) {
                String trimmed = doc.trim();
                if (trimmed.isEmpty()) continue;
                boolean isComponent = trimmed.contains("name: " + componentName);
                boolean isApiForProduct = trimmed.contains("kuadrant.io/apiproduct: " + sysName)
                        && trimmed.contains("kind: API");
                if (isComponent || isApiForProduct) {
                    if (result.length() > 0) result.append("\n---\n");
                    result.append(trimmed);
                }
            }
            if (result.length() > 0) return result.toString();
        }

        String routeName = sysName + "-route";
        String namespace = plan.resources().stream()
                .filter(r -> "HTTPRoute".equals(r.kind()) && r.name().equals(routeName))
                .findFirst().map(MigrationPlan.GeneratedResource::namespace).orElse(gatewayNamespace);
        String hostname = sysName + "." + clusterDomain;
        String gfUrl = developerHubUrl.equals("none") ? "https://gateforge." + clusterDomain : developerHubUrl;

        StringBuilder fallback = new StringBuilder();
        fallback.append("apiVersion: backstage.io/v1alpha1\n")
                .append("kind: Component\n")
                .append("metadata:\n")
                .append("  name: ").append(componentName).append("\n")
                .append("  namespace: default\n")
                .append("  description: \"").append(sysName).append(" — migrated from 3scale to Connectivity Link by GateForge\"\n")
                .append("  annotations:\n")
                .append("    kuadrant.io/namespace: ").append(namespace).append("\n")
                .append("    kuadrant.io/httproute: ").append(routeName).append("\n")
                .append("    kuadrant.io/apiproduct: ").append(sysName).append("\n")
                .append("    gateforge.io/managed-by: gateforge\n")
                .append("    gateforge.io/migration-plan-id: ").append(planId).append("\n")
                .append("    backstage.io/kubernetes-namespace: ").append(namespace).append("\n")
                .append("    backstage.io/kubernetes-id: ").append(sysName).append("\n")
                .append("    backstage.io/kubernetes-label-selector: \"app.kubernetes.io/managed-by=gateforge,gateforge.io/product=").append(sysName).append("\"\n")
                .append("    backstage.io/managed-by-origin-location: \"gateforge:").append(sysName).append("\"\n")
                .append("  tags:\n")
                .append("    - connectivity-link\n")
                .append("    - kuadrant\n")
                .append("    - gateforge-migrated\n")
                .append("spec:\n")
                .append("  type: service\n")
                .append("  lifecycle: production\n")
                .append("  owner: group:default/3scale\n")
                .append("  system: gateforge-migrated-apis\n")
                .append("  providesApis:\n")
                .append("    - ").append(sysName).append("\n");
        return fallback.toString();
    }

    public List<Map<String, String>> generateTestCommands(String planId) {
        MigrationPlan plan = getPlan(planId);
        if (plan == null) return List.of();

        List<Map<String, String>> commands = new ArrayList<>();
        Set<String> httpRoutePaths = new LinkedHashSet<>();
        String authType = "api-key";

        for (MigrationPlan.GeneratedResource res : plan.resources()) {
            if ("HTTPRoute".equals(res.kind())) {
                extractPathsFromYaml(res.yaml(), httpRoutePaths);
            }
            if ("AuthPolicy".equals(res.kind())) {
                if (res.yaml().contains("jwt:") || res.yaml().contains("oidc")) {
                    authType = "oidc";
                }
            }
        }

        for (MigrationPlan.GeneratedResource res : plan.resources()) {
            if (!"Route".equals(res.kind()) || !res.yaml().contains("host:")) continue;

            String yaml = res.yaml();
            int idx = yaml.indexOf("host:");
            if (idx < 0) continue;
            String rest = yaml.substring(idx + 5).trim();
            String host = rest.split("\\s")[0].trim();

            commands.add(Map.of(
                    "label", "Test " + res.name() + " (no auth — expect 401/403)",
                    "command", "curl -sk https://" + host + "/",
                    "type", "no-auth"
            ));

            if ("oidc".equals(authType)) {
                commands.add(Map.of(
                        "label", "Test " + res.name() + " with Bearer Token",
                        "command", "curl -sk -H \"Authorization: Bearer $TOKEN\" https://" + host + "/",
                        "type", "bearer"
                ));
            } else {
                commands.add(Map.of(
                        "label", "Test " + res.name() + " with API Key (header)",
                        "command", "curl -sk -H \"api_key: YOUR_API_KEY\" https://" + host + "/",
                        "type", "api-key"
                ));
            }

            for (String path : httpRoutePaths) {
                if ("/".equals(path)) continue;
                String curlAuth = "oidc".equals(authType)
                        ? "-H \"Authorization: Bearer $TOKEN\""
                        : "-H \"api_key: YOUR_API_KEY\"";
                commands.add(Map.of(
                        "label", "Test path " + path,
                        "command", "curl -sk " + curlAuth + " https://" + host + path,
                        "type", "path-test"
                ));
            }
        }
        return commands;
    }

    private void extractPathsFromYaml(String yaml, Set<String> paths) {
        for (String line : yaml.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("value:")) {
                String val = trimmed.substring(6).trim().replaceAll("^[\"']|[\"']$", "");
                if (val.startsWith("/")) paths.add(val);
            }
        }
    }

    private String buildCatalogInfo(List<ThreeScaleProduct> products, String strategy,
            Map<String, String> oasCache, List<MigrationPlan.GeneratedResource> resources) {
        StringBuilder sb = new StringBuilder();
        String gfUrl = developerHubUrl.equals("none") ? "https://gateforge." + clusterDomain : developerHubUrl;

        for (int i = 0; i < products.size(); i++) {
            ThreeScaleProduct p = products.get(i);
            String sysName = p.systemName();
            String routeName = sysName + "-route";
            String ns = resolveHttpRouteNamespace(sysName, resources);

            if (i > 0) sb.append("\n---\n");

            String desc = p.description() != null ? p.description().replace("\"", "'") : sysName;
            String hostname = sysName + "." + clusterDomain;

            sb.append("apiVersion: backstage.io/v1alpha1\n")
              .append("kind: Component\n")
              .append("metadata:\n")
              .append("  name: ").append(sysName).append("-product\n")
              .append("  namespace: default\n")
              .append("  description: \"").append(desc).append(" — migrated from 3scale to Connectivity Link by GateForge\"\n")
              .append("  annotations:\n")
              .append("    kuadrant.io/namespace: ").append(ns).append("\n")
              .append("    kuadrant.io/httproute: ").append(routeName).append("\n")
              .append("    kuadrant.io/apiproduct: ").append(sysName).append("\n")
              .append("    gateforge.io/managed-by: gateforge\n")
              .append("    backstage.io/kubernetes-namespace: ").append(ns).append("\n")
              .append("    backstage.io/kubernetes-id: ").append(sysName).append("\n")
              .append("    backstage.io/kubernetes-label-selector: \"app.kubernetes.io/managed-by=gateforge,gateforge.io/product=").append(sysName).append("\"\n")
              .append("    backstage.io/managed-by-origin-location: \"gateforge:").append(sysName).append("\"\n")
              .append("  tags:\n")
              .append("    - connectivity-link\n")
              .append("    - kuadrant\n")
              .append("    - gateforge-migrated\n")
              .append("  links:\n")
              .append("    - title: API Gateway Endpoint\n")
              .append("      url: https://").append(hostname).append("\n")
              .append("    - title: GateForge Dashboard\n")
              .append("      url: ").append(gfUrl).append("\n")
              .append("spec:\n")
              .append("  type: service\n")
              .append("  lifecycle: production\n")
              .append("  owner: group:default/3scale\n")
              .append("  system: gateforge-migrated-apis\n")
              .append("  providesApis:\n")
              .append("    - ").append(sysName).append("\n");
        }
        return sb.toString();
    }

    private String resolveHttpRouteNamespace(String productSysName,
            List<MigrationPlan.GeneratedResource> resources) {
        String routeName = productSysName + "-route";
        if (resources != null) {
            for (var r : resources) {
                if ("HTTPRoute".equals(r.kind()) && routeName.equals(r.name())) {
                    return r.namespace() != null ? r.namespace() : gatewayNamespace;
                }
            }
        }
        return gatewayNamespace;
    }

    private String sanitizeName(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9-]", "-");
    }

    private MigrationPlan.GeneratedResource buildPlanPolicy(
            String name, String namespace, String routeName, ThreeScaleProduct product) {

        StringBuilder plans = new StringBuilder();
        for (ThreeScaleProduct.ApplicationPlan plan : product.applicationPlans()) {
            if (!"published".equalsIgnoreCase(plan.state()) && !"hidden".equalsIgnoreCase(plan.state())) continue;
            plans.append("    - tier: \"").append(plan.systemName()).append("\"\n");
            plans.append("      predicate: |\n");
            plans.append("        has(auth.identity) && auth.identity.metadata.annotations[\"secret.kuadrant.io/plan-id\"] == \"")
                    .append(plan.systemName()).append("\"\n");
            plans.append("      limits:\n");

            for (ThreeScaleProduct.PlanLimit limit : plan.limits()) {
                switch (limit.period()) {
                    case "day" -> plans.append("        daily: ").append(limit.value()).append("\n");
                    case "month" -> plans.append("        monthly: ").append(limit.value()).append("\n");
                    case "week" -> plans.append("        weekly: ").append(limit.value()).append("\n");
                    case "year", "eternity" -> plans.append("        yearly: ").append(limit.value()).append("\n");
                    case "hour" -> {
                        plans.append("        custom:\n");
                        plans.append("          - limit: ").append(limit.value()).append("\n");
                        plans.append("            window: \"1h\"\n");
                    }
                    case "minute" -> {
                        plans.append("        custom:\n");
                        plans.append("          - limit: ").append(limit.value()).append("\n");
                        plans.append("            window: \"1m\"\n");
                    }
                }
            }
            if (plan.limits().isEmpty()) {
                plans.append("        daily: 1000\n");
            }
        }

        String yaml = """
                apiVersion: extensions.kuadrant.io/v1alpha1
                kind: PlanPolicy
                metadata:
                  name: %s
                  namespace: %s
                  labels:
                    app.kubernetes.io/managed-by: gateforge
                    "gateforge.io/product": "%s"
                spec:
                  targetRef:
                    group: gateway.networking.k8s.io
                    kind: HTTPRoute
                    name: %s
                  plans:
                %s""".formatted(name, namespace, product.systemName(), routeName, plans.toString());

        return new MigrationPlan.GeneratedResource("PlanPolicy", name, namespace, yaml);
    }

    private List<MigrationPlan.GeneratedResource> buildApiKeys(
            String sysName, String namespace, ThreeScaleProduct product) {

        List<MigrationPlan.GeneratedResource> keys = new ArrayList<>();
        for (ThreeScaleProduct.Application app : product.applications()) {
            String safeName = app.name().toLowerCase().replaceAll("[^a-z0-9-]", "-");
            if (safeName.length() > 40) safeName = safeName.substring(0, 40);
            String keyName = sysName + "-key-" + safeName;

            String yaml = """
                    apiVersion: devportal.kuadrant.io/v1alpha1
                    kind: APIKey
                    metadata:
                      name: %s
                      namespace: %s
                      labels:
                        app.kubernetes.io/managed-by: gateforge
                        "gateforge.io/product": "%s"
                    spec:
                      apiProductRef:
                        name: %s
                      planTier: "%s"
                      requestedBy:
                        userId: "%s"
                        email: "%s"
                      useCase: "Migrated from 3scale application '%s' by GateForge"
                    """.formatted(keyName, namespace, sysName, sysName,
                    app.planSystemName().isBlank() ? "default" : app.planSystemName(),
                    app.accountEmail(), app.accountEmail(), app.name());

            keys.add(new MigrationPlan.GeneratedResource("APIKey", keyName, namespace, yaml));
        }
        return keys;
    }

    private MigrationPlan.GeneratedResource buildApiProduct(
            String name, String namespace, String routeName, ThreeScaleProduct product) {

        String desc = product.description() != null && !product.description().isBlank()
                ? product.description().replace("\"", "'")
                : product.name();

        String authType = "api-key";
        if (product.authentication() != null) {
            String type = String.valueOf(product.authentication().getOrDefault("type", ""));
            if ("oidc".equalsIgnoreCase(type) || "openid_connect".equalsIgnoreCase(type)) {
                authType = "oidc";
            }
        }

        String hostname = product.systemName() + "." + clusterDomain;

        String yaml = """
                apiVersion: devportal.kuadrant.io/v1alpha1
                kind: APIProduct
                metadata:
                  name: %s
                  namespace: %s
                  annotations:
                    backstage.io/owner: "group:default/3scale"
                    backstage.io/kubernetes-namespace: %s
                    backstage.io/kubernetes-id: %s
                  labels:
                    app.kubernetes.io/managed-by: gateforge
                    "gateforge.io/product": "%s"
                    backstage.io/kubernetes-id: %s
                spec:
                  targetRef:
                    group: gateway.networking.k8s.io
                    kind: HTTPRoute
                    name: %s
                  displayName: "%s"
                  description: |
                    %s
                    Migrated from 3scale to Connectivity Link by GateForge.
                  version: "v1"
                  publishStatus: Published
                  approvalMode: automatic
                  tags:
                    - %s
                    - kuadrant
                    - gateforge-migrated
                  contact:
                    team: platform-engineering
                    email: "platform@%s"
                  documentation:
                    openAPISpecURL: "https://%s/q/openapi"
                    swaggerUI: "https://%s/q/swagger-ui"
                """.formatted(name, namespace, namespace, product.systemName(), product.systemName(), product.systemName(),
                routeName, product.name(), desc, authType, clusterDomain, hostname, hostname);

        return new MigrationPlan.GeneratedResource("APIProduct", name, namespace, yaml);
    }

    private MigrationPlan.GeneratedResource buildTelemetryPolicy(
            String name, String namespace, String routeName, ThreeScaleProduct product) {

        String authType = "api-key";
        if (product.authentication() != null) {
            String type = String.valueOf(product.authentication().getOrDefault("type", ""));
            if ("oidc".equalsIgnoreCase(type) || "openid_connect".equalsIgnoreCase(type)) {
                authType = "oidc";
            }
        }

        String yaml = """
                apiVersion: extensions.kuadrant.io/v1alpha1
                kind: TelemetryPolicy
                metadata:
                  name: %s
                  namespace: %s
                  labels:
                    app.kubernetes.io/managed-by: gateforge
                    "gateforge.io/product": "%s"
                spec:
                  targetRef:
                    group: gateway.networking.k8s.io
                    kind: HTTPRoute
                    name: %s
                  metrics:
                    default:
                      labels:
                        product: '"%s"'
                        auth_type: '"%s"'
                        plan: 'auth.identity.metadata.annotations["secret.kuadrant.io/plan-id"]'
                        user: 'auth.identity.userid'
                """.formatted(name, namespace, product.systemName(), routeName,
                product.systemName(), authType);

        return new MigrationPlan.GeneratedResource("TelemetryPolicy", name, namespace, yaml);
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
                    "gateforge.io/type": "%s"
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
            String backendSvcName, List<ResolvedBackend> resolvedBackends) {

        StringBuilder rules = new StringBuilder();

        boolean multiBackend = resolvedBackends.size() > 1;

        if (multiBackend) {
            for (ResolvedBackend rb : resolvedBackends) {
                String pathPrefix = rb.path().equals("/") ? "/" : rb.path();
                rules.append("        - matches:\n");
                rules.append("            - path:\n");
                rules.append("                type: PathPrefix\n");
                rules.append("                value: ").append(pathPrefix).append("\n");
                rules.append("          backendRefs:\n");
                rules.append("            - name: ").append(rb.svcName()).append("\n");
                rules.append("              port: 8080\n");
            }
        } else if (effectiveRules.isEmpty()) {
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
                prefixes.add(p.equals("/") ? "/" : p);
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
                    "gateforge.io/product": "%s"
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
                    "gateforge.io/product": "%s"
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
                    "gateforge.io/product": "%s"
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

                    String bName = String.valueOf(b.getOrDefault("name", ""));
                    if (!bName.isBlank()) byName.put(bName, ep);
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

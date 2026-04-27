package io.gateforge.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.gateforge.model.AuditEntry;
import io.gateforge.model.MigrationPlan;
import io.gateforge.service.ClusterRegistry;
import io.gateforge.service.GateForgeMetrics;
import io.gateforge.service.MigrationService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Path("/api/migration")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MigrationResource {

    private static final Logger LOG = Logger.getLogger(MigrationResource.class);

    @Inject
    MigrationService migrationService;

    @Inject
    ClusterRegistry clusterRegistry;

    @Inject
    GateForgeMetrics gateForgeMetrics;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "gateforge.developer-hub.scaffolder-url", defaultValue = "")
    Optional<String> scaffolderUrl;

    @ConfigProperty(name = "gateforge.developer-hub.scaffolder-token", defaultValue = "")
    Optional<String> scaffolderToken;

    @ConfigProperty(name = "gateforge.cluster-domain", defaultValue = "apps.cluster.example.com")
    String clusterDomain;

    @ConfigProperty(name = "gateforge.developer-hub.component-suffix", defaultValue = "-product")
    String componentSuffix;

    @ConfigProperty(name = "gateforge.developer-hub.enabled", defaultValue = "false")
    boolean developerHubEnabled;

    @ConfigProperty(name = "gateforge.developer-hub.url", defaultValue = "none")
    String developerHubUrl;

    private final HttpClient scaffolderClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    public record AnalyzeRequest(String gatewayStrategy, List<String> products, String targetClusterId) {}
    public record ApplyRequest(List<Integer> excludedIndexes, Map<String, String> yamlOverrides) {}
    public record ApplyResult(String planId, int applied, int failed, List<ResourceResult> results, String targetClusterId) {}
    public record ResourceResult(String kind, String name, String namespace, boolean success, String message) {}
    public record BulkRevertRequest(List<String> planIds, boolean deleteGateway) {}
    public record BulkRevertResult(int totalPlans, int totalReverted, int totalFailed, List<ApplyResult> planResults) {}

    @POST
    @Path("/analyze")
    public MigrationPlan analyze(AnalyzeRequest request) {
        String clusterId = request.targetClusterId() != null ? request.targetClusterId() : "local";
        return migrationService.analyze(
                request.gatewayStrategy() != null ? request.gatewayStrategy() : "shared",
                request.products() != null ? request.products() : List.of(),
                clusterId
        );
    }

    @POST
    @Path("/plans/{id}/apply")
    public ApplyResult applyPlan(@PathParam("id") String id, ApplyRequest request) {
        MigrationPlan plan = migrationService.getPlan(id);
        if (plan == null) {
            throw new NotFoundException("Plan not found: " + id);
        }

        Set<Integer> excluded = request != null && request.excludedIndexes() != null
                ? new HashSet<>(request.excludedIndexes()) : Set.of();
        Map<String, String> overrides = request != null && request.yamlOverrides() != null
                ? request.yamlOverrides() : Map.of();

        String clusterId = plan.targetClusterId() != null ? plan.targetClusterId() : "local";
        KubernetesClient client = clusterRegistry.getClient(clusterId);

        List<ResourceResult> results = new ArrayList<>();
        int applied = 0;
        int failed = 0;
        int skipped = 0;

        List<MigrationPlan.GeneratedResource> resources = plan.resources();
        for (int idx = 0; idx < resources.size(); idx++) {
            MigrationPlan.GeneratedResource res = resources.get(idx);
            if (excluded.contains(idx)) {
                results.add(new ResourceResult(res.kind(), res.name(), res.namespace(), true, "Skipped"));
                skipped++;
                LOG.infof("Skipped %s/%s (excluded by user)", res.kind(), res.name());
                continue;
            }
            String effectiveYaml = overrides.getOrDefault(String.valueOf(idx), res.yaml());
            try {
                applyYaml(client, effectiveYaml, res.namespace());
                results.add(new ResourceResult(res.kind(), res.name(), res.namespace(), true, "Applied"));
                applied++;

                migrationService.addAuditEntry(new AuditEntry(
                        UUID.randomUUID().toString(),
                        Instant.now(), "APPLY", res.kind(), res.name(), res.namespace(),
                        null, effectiveYaml, "GateForge Migration Wizard", clusterId
                ));
                LOG.infof("Applied %s/%s to namespace %s on cluster %s", res.kind(), res.name(), res.namespace(), clusterId);
            } catch (Exception e) {
                results.add(new ResourceResult(res.kind(), res.name(), res.namespace(), false, e.getMessage()));
                failed++;
                LOG.warnf("Failed to apply %s/%s on cluster %s: %s", res.kind(), res.name(), clusterId, e.getMessage());
            }
        }

        ApplyResult result = new ApplyResult(id, applied, failed, results, clusterId);

        for (MigrationPlan.GeneratedResource res : resources) {
            if (!"Gateway".equals(res.kind()) && !excluded.contains(resources.indexOf(res))) {
                gateForgeMetrics.recordMigration(res.name(), failed == 0 ? "applied" : "partial");
            }
        }

        if (developerHubEnabled && developerHubUrl != null && !developerHubUrl.isBlank()
                && !"none".equalsIgnoreCase(developerHubUrl.trim())) {
            postMigrationEventToDeveloperHub(plan, id);
            registerCatalogEntities(plan);
        }

        return result;
    }

    private void postMigrationEventToDeveloperHub(MigrationPlan plan, String planId) {
        try {
            String baseUrl = developerHubUrl.trim();
            if (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }
            String url = baseUrl + "/api/catalog/gateforge-entity-provider/migration-event";

            List<Map<String, String>> resourcesPayload = new ArrayList<>();
            for (MigrationPlan.GeneratedResource r : plan.resources()) {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("kind", r.kind());
                entry.put("name", r.name());
                entry.put("namespace", r.namespace() != null ? r.namespace() : "");
                resourcesPayload.add(entry);
            }

            for (String productName : plan.sourceProducts()) {
                String sysName = productName.toLowerCase().replaceAll("[^a-z0-9-]", "-");
                String routeName = sysName + "-route";
                String ns = plan.resources().stream()
                        .filter(r -> "HTTPRoute".equals(r.kind()) && routeName.equals(r.name()))
                        .findFirst()
                        .map(MigrationPlan.GeneratedResource::namespace)
                        .orElse("default");

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("products", List.of(sysName));
                payload.put("namespace", ns);
                payload.put("planId", planId);
                payload.put("resources", resourcesPayload);

                String json = objectMapper.writeValueAsString(payload);
                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json");
                if (scaffolderToken.isPresent() && !scaffolderToken.get().isBlank()) {
                    reqBuilder.header("Authorization", "Bearer " + scaffolderToken.get());
                }
                HttpRequest req = reqBuilder
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();

                HttpResponse<String> resp = scaffolderClient.send(req, HttpResponse.BodyHandlers.ofString());
                LOG.infof("Developer Hub migration-event POST for %s → HTTP %d", sysName, resp.statusCode());
                if (resp.statusCode() >= 400) {
                    LOG.warnf("Developer Hub migration-event error for %s: %s", sysName, resp.body());
                }
            }
        } catch (Exception e) {
            LOG.warnf("Failed to POST migration-event to Developer Hub: %s", e.getMessage());
        }
    }

    private void registerCatalogEntities(MigrationPlan plan) {
        String catalogYaml = plan.catalogInfoYaml();
        if (catalogYaml == null || catalogYaml.isBlank()) {
            LOG.info("No catalog-info YAML in plan, skipping catalog registration");
            return;
        }
        try {
            String baseUrl = developerHubUrl.trim();
            if (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }
            String locationUrl = baseUrl + "/api/catalog/locations";

            String gateforgeBaseUrl = baseUrl + "/api/migration/plans/" + plan.id();

            for (String productName : plan.sourceProducts()) {
                String sysName = productName.toLowerCase().replaceAll("[^a-z0-9-]", "-");
                String catalogEndpoint = gateforgeBaseUrl + "/catalog-info/" + sysName;

                Map<String, Object> locationPayload = new LinkedHashMap<>();
                locationPayload.put("type", "url");
                locationPayload.put("target", catalogEndpoint);

                String json = objectMapper.writeValueAsString(locationPayload);
                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(locationUrl))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json");
                if (scaffolderToken.isPresent() && !scaffolderToken.get().isBlank()) {
                    reqBuilder.header("Authorization", "Bearer " + scaffolderToken.get());
                }
                HttpRequest req = reqBuilder
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();

                HttpResponse<String> resp = scaffolderClient.send(req, HttpResponse.BodyHandlers.ofString());
                LOG.infof("Catalog location POST for %s → HTTP %d", sysName, resp.statusCode());
                if (resp.statusCode() >= 400) {
                    LOG.warnf("Catalog location error for %s: %s", sysName, resp.body());
                }
            }
        } catch (Exception e) {
            LOG.warnf("Failed to register catalog entities: %s", e.getMessage());
        }
    }

    private void triggerScaffolderTemplate(String templateName, Map<String, Object> values) {
        if (scaffolderUrl.isEmpty() || scaffolderUrl.get().isBlank()) {
            LOG.info("Scaffolder URL not configured, skipping template trigger");
            return;
        }
        try {
            Map<String, Object> payload = Map.of(
                    "templateRef", "template:default/" + templateName,
                    "values", values
            );

            String json = objectMapper.writeValueAsString(payload);
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(scaffolderUrl.get()))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json");

            if (scaffolderToken.isPresent() && !scaffolderToken.get().isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + scaffolderToken.get());
            }

            HttpRequest req = reqBuilder
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> resp = scaffolderClient.send(req, HttpResponse.BodyHandlers.ofString());
            LOG.infof("Scaffolder API response: %d — %s", resp.statusCode(), resp.body());

            if (resp.statusCode() >= 400) {
                throw new jakarta.ws.rs.WebApplicationException(
                        "Developer Hub Scaffolder API returned HTTP " + resp.statusCode() + ": " + resp.body(),
                        resp.statusCode());
            }
        } catch (java.net.http.HttpTimeoutException e) {
            String msg = "Developer Hub Scaffolder API timed out after 30s. The Component may not have been registered. "
                    + "Check the Scaffolder tasks in Developer Hub or retry using POST /api/migration/plans/{id}/confirm-registration.";
            LOG.error(msg, e);
            throw new jakarta.ws.rs.WebApplicationException(msg, 504);
        } catch (jakarta.ws.rs.WebApplicationException e) {
            throw e;
        } catch (Exception e) {
            String msg = "Failed to trigger Scaffolder template: " + e.getMessage();
            LOG.error(msg, e);
            throw new jakarta.ws.rs.WebApplicationException(msg, 502);
        }
    }

    private void applyYaml(KubernetesClient client, String yaml, String namespace) {
        GenericKubernetesResource generic = Serialization.unmarshal(yaml, GenericKubernetesResource.class);
        if (namespace != null && !namespace.isBlank()) {
            generic.getMetadata().setNamespace(namespace);
        }
        String apiVersion = generic.getApiVersion();
        if (apiVersion != null && apiVersion.contains("route.openshift.io")) {
            client.resource(generic).createOr(r -> r.update());
        } else {
            client.resource(generic).serverSideApply();
        }
    }

    @POST
    @Path("/plans/{id}/revert")
    public ApplyResult revertPlan(@PathParam("id") String id) {
        MigrationPlan plan = migrationService.getPlan(id);
        if (plan == null) {
            throw new NotFoundException("Plan not found: " + id);
        }

        String clusterId = plan.targetClusterId() != null ? plan.targetClusterId() : "local";
        KubernetesClient client = clusterRegistry.getClient(clusterId);

        List<ResourceResult> results = new ArrayList<>();
        int applied = 0;
        int failed = 0;

        List<MigrationPlan.GeneratedResource> reversed = new ArrayList<>(plan.resources());
        Collections.reverse(reversed);

        for (MigrationPlan.GeneratedResource res : reversed) {
            if ("Gateway".equals(res.kind())) continue;
            try {
                deleteResource(client, res.yaml(), res.namespace());
                results.add(new ResourceResult(res.kind(), res.name(), res.namespace(), true, "Deleted"));
                applied++;

                migrationService.addAuditEntry(new AuditEntry(
                        UUID.randomUUID().toString(),
                        Instant.now(), "REVERT", res.kind(), res.name(), res.namespace(),
                        res.yaml(), null, "GateForge Migration Wizard", clusterId
                ));
                LOG.infof("Reverted %s/%s from namespace %s on cluster %s", res.kind(), res.name(), res.namespace(), clusterId);
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null && msg.contains("NotFound")) {
                    results.add(new ResourceResult(res.kind(), res.name(), res.namespace(), true, "Already absent"));
                    applied++;
                } else {
                    results.add(new ResourceResult(res.kind(), res.name(), res.namespace(), false, msg));
                    failed++;
                    LOG.warnf("Failed to revert %s/%s on cluster %s: %s", res.kind(), res.name(), clusterId, msg);
                }
            }
        }

        migrationService.updatePlanStatus(id, "REVERTED");

        for (String productName : plan.sourceProducts()) {
            String sysName = productName.toLowerCase().replaceAll("[^a-z0-9-]", "-");
            String compName = sysName.endsWith(componentSuffix) ? sysName : sysName + componentSuffix;
            try {
                triggerScaffolderTemplate("gateforge-unregister-component", Map.of(
                        "componentName", compName
                ));
            } catch (Exception e) {
                LOG.warnf("Failed to unregister component %s: %s", compName, e.getMessage());
            }
        }

        return new ApplyResult(id, applied, failed, results, clusterId);
    }

    @POST
    @Path("/revert-bulk")
    public BulkRevertResult revertBulk(BulkRevertRequest request) {
        List<ApplyResult> planResults = new ArrayList<>();
        int totalReverted = 0;
        int totalFailed = 0;

        for (String planId : request.planIds()) {
            try {
                ApplyResult result = revertPlan(planId);
                planResults.add(result);
                if (result.failed() == 0) totalReverted++;
                else totalFailed++;
            } catch (Exception e) {
                planResults.add(new ApplyResult(planId, 0, 1, List.of(
                        new ResourceResult("Plan", planId, "", false, e.getMessage())
                ), "local"));
                totalFailed++;
            }
        }

        if (request.deleteGateway()) {
            for (MigrationPlan summary : migrationService.listPlans()) {
                MigrationPlan plan = migrationService.getPlan(summary.id());
                if (plan == null) {
                    continue;
                }
                String clusterId = plan.targetClusterId() != null ? plan.targetClusterId() : "local";
                KubernetesClient client = clusterRegistry.getClient(clusterId);
                for (MigrationPlan.GeneratedResource res : plan.resources()) {
                    if ("Gateway".equals(res.kind())) {
                        try {
                            deleteResource(client, res.yaml(), res.namespace());
                            LOG.infof("Deleted shared Gateway %s on cluster %s", res.name(), clusterId);
                        } catch (Exception e) {
                            LOG.warnf("Failed to delete Gateway %s on cluster %s: %s", res.name(), clusterId, e.getMessage());
                        }
                    }
                }
            }
        }

        return new BulkRevertResult(request.planIds().size(), totalReverted, totalFailed, planResults);
    }

    @GET
    @Path("/plans/{id}/catalog-info/{productName}")
    @Produces("text/yaml")
    public String getCatalogInfo(@PathParam("id") String id, @PathParam("productName") String productName) {
        String yaml = migrationService.getCatalogInfoForProduct(id, productName);
        if (yaml == null) {
            throw new NotFoundException("Plan or product not found: " + id + "/" + productName);
        }
        return yaml;
    }

    public record ConfirmRegistrationRequest(String componentYaml) {}

    @POST
    @Path("/plans/{id}/confirm-registration")
    public Map<String, String> confirmRegistration(@PathParam("id") String id, ConfirmRegistrationRequest request) {
        MigrationPlan plan = migrationService.getPlan(id);
        if (plan == null) {
            throw new NotFoundException("Plan not found: " + id);
        }

        for (String productName : plan.sourceProducts()) {
            String sysName = productName.toLowerCase().replaceAll("[^a-z0-9-]", "-");
            String compName = sysName.endsWith(componentSuffix) ? sysName : sysName + componentSuffix;
            String routeName = sysName + "-route";
            String namespace = plan.resources().stream()
                    .filter(r -> "HTTPRoute".equals(r.kind()) && r.name().equals(routeName))
                    .findFirst().map(MigrationPlan.GeneratedResource::namespace).orElse("default");

            Map<String, Object> templateValues = new LinkedHashMap<>(Map.of(
                    "planId", id,
                    "productName", sysName,
                    "componentName", compName,
                    "namespace", namespace,
                    "owner", "group:default/3scale",
                    "clusterDomain", clusterDomain
            ));
            if (request != null && request.componentYaml() != null && !request.componentYaml().isBlank()) {
                templateValues.put("componentYaml", request.componentYaml());
            }
            triggerScaffolderTemplate("gateforge-register-component", templateValues);
        }

        return Map.of("status", "registered", "planId", id);
    }

    @GET
    @Path("/plans/{id}/drift")
    public List<Map<String, Object>> checkDrift(@PathParam("id") String id) {
        MigrationPlan plan = migrationService.getPlan(id);
        if (plan == null) {
            throw new NotFoundException("Plan not found: " + id);
        }

        String clusterId = plan.targetClusterId() != null ? plan.targetClusterId() : "local";
        KubernetesClient client = clusterRegistry.getClient(clusterId);

        List<Map<String, Object>> driftReport = new ArrayList<>();
        for (MigrationPlan.GeneratedResource res : plan.resources()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("kind", res.kind());
            entry.put("name", res.name());
            entry.put("namespace", res.namespace());
            try {
                GenericKubernetesResource generic = Serialization.unmarshal(res.yaml(), GenericKubernetesResource.class);
                if (res.namespace() != null && !res.namespace().isBlank()) {
                    generic.getMetadata().setNamespace(res.namespace());
                }
                var existing = client.resource(generic).get();
                if (existing != null) {
                    entry.put("status", "in-sync");
                } else {
                    entry.put("status", "missing");
                }
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null && msg.contains("NotFound")) {
                    entry.put("status", "missing");
                } else {
                    entry.put("status", "error");
                    entry.put("message", msg);
                }
            }
            driftReport.add(entry);
        }
        return driftReport;
    }

    @GET
    @Path("/plans/{id}/test-commands")
    public List<Map<String, String>> getTestCommands(@PathParam("id") String id) {
        return migrationService.generateTestCommands(id);
    }

    private void deleteResource(KubernetesClient client, String yaml, String namespace) {
        GenericKubernetesResource generic = Serialization.unmarshal(yaml, GenericKubernetesResource.class);
        if (namespace != null && !namespace.isBlank()) {
            generic.getMetadata().setNamespace(namespace);
        }
        client.resource(generic).delete();
    }

    @GET
    @Path("/plans")
    public List<MigrationPlan> listPlans() {
        return migrationService.listPlans();
    }

    @GET
    @Path("/plans/{id}")
    public MigrationPlan getPlan(@PathParam("id") String id) {
        MigrationPlan plan = migrationService.getPlan(id);
        if (plan == null) {
            throw new NotFoundException("Plan not found: " + id);
        }
        return plan;
    }
}

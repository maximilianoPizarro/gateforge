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

    @ConfigProperty(name = "gateforge.developer-hub.plugin-webhook-url", defaultValue = "")
    Optional<String> devhubWebhookUrl;

    @ConfigProperty(name = "gateforge.cluster-domain", defaultValue = "apps.cluster.example.com")
    String clusterDomain;

    private final HttpClient webhookClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

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

        notifyDevHubPlugin("migration-applied", plan, clusterId);

        return result;
    }

    private void notifyDevHubPlugin(String event, MigrationPlan plan, String clusterId) {
        if (devhubWebhookUrl.isEmpty() || devhubWebhookUrl.get().isBlank()) return;
        try {
            List<Map<String, Object>> products = new ArrayList<>();
            for (String productName : plan.sourceProducts()) {
                products.add(Map.of(
                        "systemName", productName.toLowerCase().replaceAll("[^a-z0-9-]", "-"),
                        "name", productName,
                        "namespace", plan.resources().stream()
                                .filter(r -> "HTTPRoute".equals(r.kind()))
                                .findFirst().map(MigrationPlan.GeneratedResource::namespace).orElse("default"),
                        "authType", "apiKey"
                ));
            }

            List<Map<String, String>> resources = plan.resources().stream()
                    .filter(r -> Set.of("HTTPRoute", "APIProduct", "PlanPolicy", "APIKey").contains(r.kind()))
                    .map(r -> Map.of("kind", r.kind(), "name", r.name(), "namespace", r.namespace()))
                    .toList();

            Map<String, Object> payload = Map.of(
                    "event", event,
                    "planId", plan.id(),
                    "products", products,
                    "resources", resources,
                    "clusterDomain", clusterDomain
            );

            String json = objectMapper.writeValueAsString(payload);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(devhubWebhookUrl.get()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            webhookClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(resp -> LOG.infof("DevHub webhook response: %d", resp.statusCode()))
                    .exceptionally(ex -> {
                        LOG.warnf("DevHub webhook failed: %s", ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            LOG.warnf("Failed to send DevHub webhook: %s", e.getMessage());
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
        notifyDevHubPlugin("migration-reverted", plan, clusterId);
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
            for (MigrationPlan plan : migrationService.listPlans()) {
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

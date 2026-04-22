package io.gateforge.resource;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.gateforge.model.AuditEntry;
import io.gateforge.model.MigrationPlan;
import io.gateforge.service.MigrationService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;
import java.util.Map;

@Path("/api/migration")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MigrationResource {

    private static final Logger LOG = Logger.getLogger(MigrationResource.class);

    @Inject
    MigrationService migrationService;

    @Inject
    KubernetesClient kubernetesClient;

    public record AnalyzeRequest(String gatewayStrategy, List<String> products) {}
    public record ApplyResult(String planId, int applied, int failed, List<ResourceResult> results) {}
    public record ResourceResult(String kind, String name, String namespace, boolean success, String message) {}
    public record BulkRevertRequest(List<String> planIds, boolean deleteGateway) {}
    public record BulkRevertResult(int totalPlans, int totalReverted, int totalFailed, List<ApplyResult> planResults) {}

    @POST
    @Path("/analyze")
    public MigrationPlan analyze(AnalyzeRequest request) {
        return migrationService.analyze(
                request.gatewayStrategy() != null ? request.gatewayStrategy() : "shared",
                request.products() != null ? request.products() : List.of()
        );
    }

    @POST
    @Path("/plans/{id}/apply")
    public ApplyResult applyPlan(@PathParam("id") String id) {
        MigrationPlan plan = migrationService.getPlan(id);
        if (plan == null) {
            throw new NotFoundException("Plan not found: " + id);
        }

        List<ResourceResult> results = new ArrayList<>();
        int applied = 0;
        int failed = 0;

        for (MigrationPlan.GeneratedResource res : plan.resources()) {
            try {
                applyYaml(res.yaml(), res.namespace());
                results.add(new ResourceResult(res.kind(), res.name(), res.namespace(), true, "Applied"));
                applied++;

                migrationService.addAuditEntry(new AuditEntry(
                        UUID.randomUUID().toString(),
                        Instant.now(), "APPLY", res.kind(), res.name(), res.namespace(),
                        null, res.yaml(), "GateForge Migration Wizard"
                ));
                LOG.infof("Applied %s/%s to namespace %s", res.kind(), res.name(), res.namespace());
            } catch (Exception e) {
                results.add(new ResourceResult(res.kind(), res.name(), res.namespace(), false, e.getMessage()));
                failed++;
                LOG.warnf("Failed to apply %s/%s: %s", res.kind(), res.name(), e.getMessage());
            }
        }

        return new ApplyResult(id, applied, failed, results);
    }

    private void applyYaml(String yaml, String namespace) {
        GenericKubernetesResource generic = Serialization.unmarshal(yaml, GenericKubernetesResource.class);
        if (namespace != null && !namespace.isBlank()) {
            generic.getMetadata().setNamespace(namespace);
        }
        kubernetesClient.resource(generic).serverSideApply();
    }

    @POST
    @Path("/plans/{id}/revert")
    public ApplyResult revertPlan(@PathParam("id") String id) {
        MigrationPlan plan = migrationService.getPlan(id);
        if (plan == null) {
            throw new NotFoundException("Plan not found: " + id);
        }

        List<ResourceResult> results = new ArrayList<>();
        int applied = 0;
        int failed = 0;

        List<MigrationPlan.GeneratedResource> reversed = new ArrayList<>(plan.resources());
        Collections.reverse(reversed);

        for (MigrationPlan.GeneratedResource res : reversed) {
            if ("Gateway".equals(res.kind())) continue;
            try {
                deleteResource(res.yaml(), res.namespace());
                results.add(new ResourceResult(res.kind(), res.name(), res.namespace(), true, "Deleted"));
                applied++;

                migrationService.addAuditEntry(new AuditEntry(
                        UUID.randomUUID().toString(),
                        Instant.now(), "REVERT", res.kind(), res.name(), res.namespace(),
                        res.yaml(), null, "GateForge Migration Wizard"
                ));
                LOG.infof("Reverted %s/%s from namespace %s", res.kind(), res.name(), res.namespace());
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null && msg.contains("NotFound")) {
                    results.add(new ResourceResult(res.kind(), res.name(), res.namespace(), true, "Already absent"));
                    applied++;
                } else {
                    results.add(new ResourceResult(res.kind(), res.name(), res.namespace(), false, msg));
                    failed++;
                    LOG.warnf("Failed to revert %s/%s: %s", res.kind(), res.name(), msg);
                }
            }
        }

        migrationService.updatePlanStatus(id, "REVERTED");
        return new ApplyResult(id, applied, failed, results);
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
                )));
                totalFailed++;
            }
        }

        if (request.deleteGateway()) {
            for (MigrationPlan plan : migrationService.listPlans()) {
                for (MigrationPlan.GeneratedResource res : plan.resources()) {
                    if ("Gateway".equals(res.kind())) {
                        try {
                            deleteResource(res.yaml(), res.namespace());
                            LOG.infof("Deleted shared Gateway %s", res.name());
                        } catch (Exception e) {
                            LOG.warnf("Failed to delete Gateway %s: %s", res.name(), e.getMessage());
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

    private void deleteResource(String yaml, String namespace) {
        GenericKubernetesResource generic = Serialization.unmarshal(yaml, GenericKubernetesResource.class);
        if (namespace != null && !namespace.isBlank()) {
            generic.getMetadata().setNamespace(namespace);
        }
        kubernetesClient.resource(generic).delete();
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

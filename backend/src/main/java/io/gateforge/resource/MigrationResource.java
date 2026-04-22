package io.gateforge.resource;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.gateforge.model.AuditEntry;
import io.gateforge.model.MigrationPlan;
import io.gateforge.service.MigrationService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
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
    KubernetesClient kubernetesClient;

    public record AnalyzeRequest(String gatewayStrategy, List<String> products) {}
    public record ApplyResult(String planId, int applied, int failed, List<ResourceResult> results) {}
    public record ResourceResult(String kind, String name, String namespace, boolean success, String message) {}

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
                var items = kubernetesClient.load(
                        new ByteArrayInputStream(res.yaml().getBytes(StandardCharsets.UTF_8))
                ).items();

                for (var item : items) {
                    if (res.namespace() != null && !res.namespace().isBlank()) {
                        item.getMetadata().setNamespace(res.namespace());
                    }
                }

                kubernetesClient.resourceList(items).createOrReplace();
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

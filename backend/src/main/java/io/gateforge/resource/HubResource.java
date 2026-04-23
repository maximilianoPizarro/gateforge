package io.gateforge.resource;

import io.gateforge.model.AuditEntry;
import io.gateforge.model.MigrationPlan;
import io.gateforge.model.TargetCluster;
import io.gateforge.model.ThreeScaleSource;
import io.gateforge.service.ClusterRegistry;
import io.gateforge.service.MigrationService;
import io.gateforge.service.ThreeScaleSourceRegistry;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.*;

@Path("/api/hub")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class HubResource {

    @Inject
    MigrationService migrationService;

    @Inject
    ClusterRegistry clusterRegistry;

    @Inject
    ThreeScaleSourceRegistry sourceRegistry;

    @GET
    @Path("/overview")
    public Map<String, Object> getHubOverview() {
        Map<String, Object> overview = new LinkedHashMap<>();

        List<TargetCluster> clusters = clusterRegistry.listClusters();
        List<ThreeScaleSource> sources = sourceRegistry.listSources();
        List<MigrationPlan> plans = migrationService.listPlans();
        List<AuditEntry> audit = migrationService.getAuditLog();

        overview.put("totalClusters", clusters.size());
        overview.put("totalSources", sources.size());
        overview.put("totalPlans", plans.size());
        overview.put("activePlans", plans.stream().filter(p -> "ACTIVE".equals(p.status())).count());
        overview.put("revertedPlans", plans.stream().filter(p -> "REVERTED".equals(p.status())).count());
        overview.put("totalAuditEntries", audit.size());

        Map<String, Long> plansByCluster = new LinkedHashMap<>();
        for (MigrationPlan plan : plans) {
            String cid = plan.targetClusterId() != null ? plan.targetClusterId() : "local";
            plansByCluster.merge(cid, 1L, Long::sum);
        }
        overview.put("plansByCluster", plansByCluster);

        Map<String, Long> auditByCluster = new LinkedHashMap<>();
        for (AuditEntry entry : audit) {
            String cid = entry.targetClusterId() != null ? entry.targetClusterId() : "local";
            auditByCluster.merge(cid, 1L, Long::sum);
        }
        overview.put("auditByCluster", auditByCluster);

        return overview;
    }

    @GET
    @Path("/audit")
    public List<AuditEntry> getFederatedAudit(
            @QueryParam("cluster") String clusterId,
            @QueryParam("action") String action,
            @QueryParam("limit") @DefaultValue("100") int limit) {

        List<AuditEntry> all = migrationService.getAuditLog();
        return all.stream()
                .filter(e -> clusterId == null || clusterId.isBlank() || clusterId.equals(e.targetClusterId()))
                .filter(e -> action == null || action.isBlank() || action.equalsIgnoreCase(e.action()))
                .limit(limit)
                .toList();
    }

    @GET
    @Path("/plans")
    public List<MigrationPlan> getFederatedPlans(
            @QueryParam("cluster") String clusterId,
            @QueryParam("status") String status) {

        List<MigrationPlan> all = migrationService.listPlans();
        return all.stream()
                .filter(p -> clusterId == null || clusterId.isBlank() || clusterId.equals(p.targetClusterId()))
                .filter(p -> status == null || status.isBlank() || status.equalsIgnoreCase(p.status()))
                .toList();
    }

    @GET
    @Path("/topology")
    public Map<String, Object> getTopology() {
        Map<String, Object> topology = new LinkedHashMap<>();

        List<Map<String, Object>> clusterNodes = new ArrayList<>();
        for (TargetCluster cluster : clusterRegistry.listClusters()) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", cluster.id());
            node.put("label", cluster.label());
            node.put("type", "local".equals(cluster.id()) ? "hub" : "spoke");
            node.put("authType", cluster.authType());
            node.put("enabled", cluster.enabled());

            Map<String, Object> validation = clusterRegistry.validateAccess(cluster.id());
            node.put("connected", validation.getOrDefault("connected", false));
            clusterNodes.add(node);
        }
        topology.put("clusters", clusterNodes);

        List<Map<String, Object>> sourceNodes = new ArrayList<>();
        for (ThreeScaleSource source : sourceRegistry.listSources()) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", source.id());
            node.put("label", source.label());
            node.put("adminUrl", source.adminUrl());
            node.put("enabled", source.enabled());
            sourceNodes.add(node);
        }
        topology.put("sources", sourceNodes);

        return topology;
    }
}

package io.gateforge.resource;

import io.gateforge.model.ProjectInfo;
import io.gateforge.model.TargetCluster;
import io.gateforge.service.ClusterRegistry;
import io.gateforge.service.ClusterService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Path("/api/cluster")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ClusterResource {

    @Inject
    ClusterService clusterService;

    @Inject
    ClusterRegistry clusterRegistry;

    @ConfigProperty(name = "gateforge.developer-hub.enabled", defaultValue = "false")
    boolean developerHubEnabled;

    @ConfigProperty(name = "gateforge.developer-hub.url", defaultValue = "none")
    String developerHubUrl;

    @GET
    @Path("/projects")
    public List<ProjectInfo> listProjects() {
        return clusterService.listProjects();
    }

    @GET
    @Path("/projects/{name}")
    public ProjectInfo getProject(@PathParam("name") String name) {
        ProjectInfo project = clusterService.getProject(name);
        if (project == null) {
            throw new NotFoundException("Project not found: " + name);
        }
        return project;
    }

    @GET
    @Path("/features")
    public Map<String, Object> getFeatures() {
        Map<String, Object> features = new LinkedHashMap<>();
        Map<String, Object> devHub = new LinkedHashMap<>();
        devHub.put("enabled", developerHubEnabled);
        devHub.put("url", "none".equals(developerHubUrl) ? "" : developerHubUrl);
        features.put("developerHub", devHub);
        return features;
    }

    @GET
    @Path("/targets")
    public List<TargetCluster> listTargetClusters() {
        return clusterRegistry.listClusters();
    }

    @POST
    @Path("/targets")
    public TargetCluster addTargetCluster(TargetCluster cluster) {
        clusterRegistry.addCluster(cluster);
        return cluster;
    }

    @DELETE
    @Path("/targets/{id}")
    public void removeTargetCluster(@PathParam("id") String id) {
        if ("local".equals(id)) {
            throw new BadRequestException("Cannot remove local cluster");
        }
        clusterRegistry.removeCluster(id);
    }

    @GET
    @Path("/targets/{id}/validate")
    public Map<String, Object> validateTargetCluster(@PathParam("id") String id) {
        return clusterRegistry.validateAccess(id);
    }
}

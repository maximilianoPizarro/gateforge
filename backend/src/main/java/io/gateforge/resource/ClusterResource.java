package io.gateforge.resource;

import io.gateforge.model.ProjectInfo;
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
public class ClusterResource {

    @Inject
    ClusterService clusterService;

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
}

package io.gateforge.resource;

import io.gateforge.model.ProjectInfo;
import io.gateforge.service.ClusterService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/api/cluster")
@Produces(MediaType.APPLICATION_JSON)
public class ClusterResource {

    @Inject
    ClusterService clusterService;

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
}

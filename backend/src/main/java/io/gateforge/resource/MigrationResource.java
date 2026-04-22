package io.gateforge.resource;

import io.gateforge.model.MigrationPlan;
import io.gateforge.service.MigrationService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/api/migration")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MigrationResource {

    @Inject
    MigrationService migrationService;

    public record AnalyzeRequest(String gatewayStrategy, List<String> products) {}

    @POST
    @Path("/analyze")
    public MigrationPlan analyze(AnalyzeRequest request) {
        return migrationService.analyze(
                request.gatewayStrategy() != null ? request.gatewayStrategy() : "shared",
                request.products() != null ? request.products() : List.of()
        );
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

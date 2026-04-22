package io.gateforge.resource;

import io.gateforge.model.AuditEntry;
import io.gateforge.service.AuditService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/api/audit")
@Produces(MediaType.APPLICATION_JSON)
public class AuditResource {

    @Inject
    AuditService auditService;

    @GET
    @Path("/reports")
    public List<AuditEntry> listReports() {
        return auditService.getAll();
    }
}

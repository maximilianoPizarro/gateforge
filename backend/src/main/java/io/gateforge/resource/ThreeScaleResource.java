package io.gateforge.resource;

import io.gateforge.model.ThreeScaleProduct;
import io.gateforge.model.ThreeScaleSource;
import io.gateforge.service.ThreeScaleService;
import io.gateforge.service.ThreeScaleSourceRegistry;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;

@Path("/api/threescale")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ThreeScaleResource {

    @Inject
    ThreeScaleService threeScaleService;

    @Inject
    ThreeScaleSourceRegistry sourceRegistry;

    @GET
    @Path("/products")
    public List<ThreeScaleProduct> listProducts() {
        return threeScaleService.listProducts();
    }

    @GET
    @Path("/products/{namespace}/{name}")
    public ThreeScaleProduct getProduct(@PathParam("namespace") String namespace,
                                        @PathParam("name") String name) {
        ThreeScaleProduct product = threeScaleService.getProduct(name, namespace);
        if (product == null) {
            throw new NotFoundException("Product not found: " + name);
        }
        return product;
    }

    @GET
    @Path("/backends")
    public List<Map<String, Object>> listBackends() {
        return threeScaleService.listBackendsCombined();
    }

    @GET
    @Path("/status")
    public Map<String, Object> getStatus() {
        return threeScaleService.getAdminApiStatus();
    }

    @GET
    @Path("/sources")
    public List<ThreeScaleSource> listSources() {
        return sourceRegistry.listSources();
    }

    @POST
    @Path("/sources")
    public ThreeScaleSource addSource(ThreeScaleSource source) {
        sourceRegistry.addSource(source);
        return source;
    }

    @DELETE
    @Path("/sources/{id}")
    public void removeSource(@PathParam("id") String id) {
        if ("default".equals(id)) {
            throw new BadRequestException("Cannot remove default source");
        }
        sourceRegistry.removeSource(id);
    }

    @GET
    @Path("/sources/{id}/status")
    public Map<String, Object> getSourceStatus(@PathParam("id") String id) {
        return sourceRegistry.getSourceStatus(id);
    }
}

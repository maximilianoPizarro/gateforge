package io.gateforge.resource;

import io.gateforge.model.APICastConfig;
import io.gateforge.model.MigrationPlan;
import io.gateforge.service.APICastDiscoveryService;
import io.gateforge.service.APICastToIstioMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Path("/api/apicast")
@Produces(MediaType.APPLICATION_JSON)
public class APICastResource {

    @Inject
    APICastDiscoveryService discoveryService;

    @Inject
    APICastToIstioMapper mapper;

    @GET
    @Path("/discover")
    public Response discoverAll() {
        List<APICastConfig> configs = discoveryService.discoverAllAPIManagers();
        List<Map<String, Object>> discovered = configs.stream()
                .map(this::toDiscoveryDTO)
                .collect(Collectors.toList());
        return Response.ok(Map.of("total", discovered.size(), "apiManagers", discovered)).build();
    }

    @GET
    @Path("/discover/{namespace}")
    public Response discoverByNamespace(@PathParam("namespace") String namespace) {
        List<APICastConfig> configs = discoveryService.discoverByNamespace(namespace);
        List<Map<String, Object>> discovered = configs.stream()
                .map(this::toDiscoveryDTO)
                .collect(Collectors.toList());
        return Response.ok(Map.of("namespace", namespace, "total", discovered.size(),
                "apiManagers", discovered)).build();
    }

    @GET
    @Path("/analyze/{namespace}/{name}")
    public Response analyze(@PathParam("namespace") String namespace, @PathParam("name") String name) {
        APICastConfig config = discoveryService.discoverByName(name, namespace);
        if (config == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "APIManager not found or not self-managed",
                            "namespace", namespace, "name", name))
                    .build();
        }
        return Response.ok(config).build();
    }

    @POST
    @Path("/map")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response mapAPICastToIstio(Map<String, String> request) {
        String namespace = request.get("namespace");
        String name = request.get("name");
        if (namespace == null || name == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "namespace and name are required")).build();
        }

        APICastConfig config = discoveryService.discoverByName(name, namespace);
        if (config == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "APIManager not found")).build();
        }

        List<MigrationPlan.GeneratedResource> resources = mapper.mapAPICastToIstio(config);
        return Response.ok(Map.of(
                "apiManager", name,
                "namespace", namespace,
                "total", resources.size(),
                "resources", resources
        )).build();
    }

    @POST
    @Path("/map-all")
    public Response mapAll() {
        List<APICastConfig> configs = discoveryService.discoverAllAPIManagers();
        var plans = mapper.mapMultipleAPICasts(configs);
        int total = plans.stream().mapToInt(List::size).sum();
        return Response.ok(Map.of("apiManagers", configs.size(), "totalResources", total,
                "plans", plans)).build();
    }

    private Map<String, Object> toDiscoveryDTO(APICastConfig config) {
        return Map.ofEntries(
                Map.entry("name", config.apiManagerName()),
                Map.entry("namespace", config.namespace()),
                Map.entry("status", config.status() != null ? config.status() : "ready"),
                Map.entry("stagingReplicas", config.stagingSpec() != null ? config.stagingSpec().replicas() : 0),
                Map.entry("productionReplicas", config.productionSpec() != null ? config.productionSpec().replicas() : 0),
                Map.entry("customPolicies", config.customPolicies() != null ? config.customPolicies().size() : 0),
                Map.entry("tlsEnabled", config.tls() != null && config.tls().verify()),
                Map.entry("tracingEnabled", config.openTracing() != null && config.openTracing().enabled()),
                Map.entry("productsCount", config.productsCount()),
                Map.entry("apiCastPods", config.apiCastPods())
        );
    }
}

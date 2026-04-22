package io.gateforge.mcp;

import io.gateforge.service.KuadrantCtlService;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.inject.Inject;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public class ConnectivityLinkMcpTools {

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    KuadrantCtlService kuadrantCtlService;

    @Tool(description = "Apply a Kubernetes/Kuadrant YAML resource to the cluster")
    public String applyResource(
            @ToolArg(description = "YAML content of the resource to apply") String yaml) {
        try {
            var resources = kubernetesClient.load(
                    new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))).items();
            var result = kubernetesClient.resourceList(resources).createOrReplace();
            return "Applied %d resource(s) successfully.".formatted(result.size());
        } catch (Exception e) {
            return "ERROR applying resource: " + e.getMessage();
        }
    }

    @Tool(description = "Generate an HTTPRoute from an OpenAPI spec using kuadrantctl")
    public String generateHttpRoute(
            @ToolArg(description = "OpenAPI 3.x spec content (YAML or JSON)") String oasContent) {
        return kuadrantCtlService.generateHttpRoute(oasContent);
    }

    @Tool(description = "Generate an AuthPolicy from an OpenAPI spec using kuadrantctl")
    public String generateAuthPolicy(
            @ToolArg(description = "OpenAPI 3.x spec content (YAML or JSON)") String oasContent) {
        return kuadrantCtlService.generateAuthPolicy(oasContent);
    }

    @Tool(description = "Generate a RateLimitPolicy from an OpenAPI spec using kuadrantctl")
    public String generateRateLimitPolicy(
            @ToolArg(description = "OpenAPI 3.x spec content (YAML or JSON)") String oasContent) {
        return kuadrantCtlService.generateRateLimitPolicy(oasContent);
    }

    @Tool(description = "Get Kuadrant topology for a namespace")
    public String getTopology(
            @ToolArg(description = "Namespace to inspect") String namespace) {
        return kuadrantCtlService.topology(namespace);
    }
}

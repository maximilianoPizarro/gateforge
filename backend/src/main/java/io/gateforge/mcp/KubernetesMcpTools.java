package io.gateforge.mcp;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.inject.Inject;

import java.util.stream.Collectors;

public class KubernetesMcpTools {

    @Inject
    KubernetesClient kubernetesClient;

    @Tool(description = "List all namespaces/projects in the cluster")
    public String listNamespaces() {
        return kubernetesClient.namespaces().list().getItems().stream()
                .map(ns -> "- %s (%s)".formatted(
                        ns.getMetadata().getName(),
                        ns.getStatus() != null ? ns.getStatus().getPhase() : "Unknown"))
                .collect(Collectors.joining("\n"));
    }

    @Tool(description = "List services in a specific namespace")
    public String listServices(
            @ToolArg(description = "Namespace to list services from") String namespace) {
        return kubernetesClient.services().inNamespace(namespace).list().getItems().stream()
                .map(svc -> "- %s (type: %s, ports: %s)".formatted(
                        svc.getMetadata().getName(),
                        svc.getSpec().getType(),
                        svc.getSpec().getPorts().stream()
                                .map(p -> "%d/%s".formatted(p.getPort(), p.getProtocol()))
                                .collect(Collectors.joining(","))))
                .collect(Collectors.joining("\n"));
    }

    @Tool(description = "Get the status of a specific Kubernetes resource")
    public String getResourceStatus(
            @ToolArg(description = "Resource kind (e.g. Deployment, Service)") String kind,
            @ToolArg(description = "Resource name") String name,
            @ToolArg(description = "Namespace") String namespace) {
        try {
            var resource = kubernetesClient.genericKubernetesResources(
                            kind.toLowerCase() + "s", "")
                    .inNamespace(namespace).withName(name).get();
            if (resource == null) {
                return "%s '%s' not found in namespace '%s'.".formatted(kind, name, namespace);
            }
            return "Found %s '%s' in '%s'. Status: %s".formatted(
                    kind, name, namespace, resource.getAdditionalProperties().getOrDefault("status", "N/A"));
        } catch (Exception e) {
            return "Error querying %s '%s': %s".formatted(kind, name, e.getMessage());
        }
    }
}

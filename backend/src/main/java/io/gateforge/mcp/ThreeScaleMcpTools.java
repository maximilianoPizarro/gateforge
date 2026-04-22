package io.gateforge.mcp;

import io.gateforge.model.ThreeScaleProduct;
import io.gateforge.service.ThreeScaleService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.inject.Inject;

import java.util.List;
import java.util.stream.Collectors;

public class ThreeScaleMcpTools {

    @Inject
    ThreeScaleService threeScaleService;

    @Tool(description = "List all 3scale Product CRs discovered in the cluster")
    public String listProducts() {
        List<ThreeScaleProduct> products = threeScaleService.listProducts();
        if (products.isEmpty()) {
            return "No 3scale Products found in the cluster.";
        }
        return products.stream()
                .map(p -> "- %s (ns: %s, system: %s, backends: %d, rules: %d)".formatted(
                        p.name(), p.namespace(), p.systemName(),
                        p.backendUsages().size(), p.mappingRules().size()))
                .collect(Collectors.joining("\n"));
    }

    @Tool(description = "Get detailed information about a specific 3scale Product")
    public String getProductDetails(
            @ToolArg(description = "Product name") String name,
            @ToolArg(description = "Namespace") String namespace) {
        ThreeScaleProduct product = threeScaleService.getProduct(name, namespace);
        if (product == null) {
            return "Product '%s' not found in namespace '%s'.".formatted(name, namespace);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Product: ").append(product.name()).append("\n");
        sb.append("Namespace: ").append(product.namespace()).append("\n");
        sb.append("System Name: ").append(product.systemName()).append("\n");
        sb.append("Description: ").append(product.description()).append("\n");
        sb.append("Deployment: ").append(product.deploymentOption()).append("\n");
        sb.append("\nMapping Rules:\n");
        product.mappingRules().forEach(r ->
                sb.append("  - %s %s -> %s (delta: %d)\n".formatted(
                        r.httpMethod(), r.pattern(), r.metricRef(), r.delta())));
        sb.append("\nBackend Usages:\n");
        product.backendUsages().forEach(b ->
                sb.append("  - %s (path: %s)\n".formatted(b.backendName(), b.path())));
        sb.append("\nAuthentication: ").append(product.authentication());
        return sb.toString();
    }

    @Tool(description = "List all 3scale Backend CRs in the cluster")
    public String listBackends() {
        var backends = threeScaleService.listBackends();
        if (backends.isEmpty()) {
            return "No 3scale Backends found in the cluster.";
        }
        return backends.stream()
                .map(b -> "- %s (ns: %s)".formatted(
                        b.getMetadata().getName(), b.getMetadata().getNamespace()))
                .collect(Collectors.joining("\n"));
    }
}

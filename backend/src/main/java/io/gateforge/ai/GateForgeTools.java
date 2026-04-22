package io.gateforge.ai;

import dev.langchain4j.agent.tool.Tool;
import io.gateforge.model.ProjectInfo;
import io.gateforge.model.ThreeScaleProduct;
import io.gateforge.service.ClusterService;
import io.gateforge.service.KuadrantCtlService;
import io.gateforge.service.ThreeScaleAdminApiClient;
import io.gateforge.service.ThreeScaleService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class GateForgeTools {

    @Inject
    ThreeScaleService threeScaleService;

    @Inject
    ClusterService clusterService;

    @Inject
    KuadrantCtlService kuadrantCtlService;

    @Inject
    ThreeScaleAdminApiClient adminApiClient;

    @Tool("List all 3scale products discovered from both CRDs and Admin API, showing name, namespace, source, mapping rules count, and backend usages count")
    public String listThreeScaleProducts() {
        List<ThreeScaleProduct> products = threeScaleService.listProducts();
        if (products.isEmpty()) {
            return "No 3scale products found in the cluster (checked CRDs and Admin API).";
        }
        return products.stream()
                .map(p -> "- %s (namespace: %s, source: %s, systemName: %s, %d mapping rules, %d backends)".formatted(
                        p.name(), p.namespace(), p.source(), p.systemName(),
                        p.mappingRules().size(), p.backendUsages().size()))
                .collect(Collectors.joining("\n", "Found %d products:\n".formatted(products.size()), ""));
    }

    @Tool("Get detailed configuration of a specific 3scale product including mapping rules, backend usages, and authentication settings")
    public String describeProduct(String productName) {
        List<ThreeScaleProduct> products = threeScaleService.listProducts();
        ThreeScaleProduct product = products.stream()
                .filter(p -> p.name().equalsIgnoreCase(productName) || p.systemName().equalsIgnoreCase(productName))
                .findFirst().orElse(null);
        if (product == null) {
            return "Product '%s' not found. Available products: %s".formatted(
                    productName,
                    products.stream().map(ThreeScaleProduct::name).collect(Collectors.joining(", ")));
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Product: ").append(product.name()).append("\n");
        sb.append("Source: ").append(product.source()).append("\n");
        sb.append("Namespace: ").append(product.namespace()).append("\n");
        sb.append("System Name: ").append(product.systemName()).append("\n");
        sb.append("Description: ").append(product.description()).append("\n");
        sb.append("Deployment: ").append(product.deploymentOption()).append("\n");
        sb.append("\nMapping Rules (").append(product.mappingRules().size()).append("):\n");
        product.mappingRules().forEach(r ->
                sb.append("  %s %s -> metric: %s (delta: %d)\n".formatted(
                        r.httpMethod(), r.pattern(), r.metricRef(), r.delta())));
        sb.append("\nBackend Usages (").append(product.backendUsages().size()).append("):\n");
        product.backendUsages().forEach(b ->
                sb.append("  %s -> path: %s\n".formatted(b.backendName(), b.path())));
        if (product.authentication() != null && !product.authentication().isEmpty()) {
            sb.append("\nAuthentication: ").append(product.authentication());
        }
        return sb.toString();
    }

    @Tool("List all 3scale backends discovered from CRDs and Admin API")
    public String listThreeScaleBackends() {
        List<Map<String, Object>> backends = threeScaleService.listBackendsCombined();
        if (backends.isEmpty()) {
            return "No 3scale backends found.";
        }
        return backends.stream()
                .map(b -> "- %s (source: %s, endpoint: %s)".formatted(
                        b.getOrDefault("name", "unknown"),
                        b.getOrDefault("source", "unknown"),
                        b.getOrDefault("privateEndpoint", b.getOrDefault("namespace", "N/A"))))
                .collect(Collectors.joining("\n", "Found %d backends:\n".formatted(backends.size()), ""));
    }

    @Tool("List all OpenShift projects/namespaces showing which ones have 3scale or Kuadrant resources")
    public String listProjects() {
        List<ProjectInfo> projects = clusterService.listProjects();
        long threeScaleCount = projects.stream().filter(ProjectInfo::hasThreeScale).count();
        long kuadrantCount = projects.stream().filter(ProjectInfo::hasKuadrant).count();

        StringBuilder sb = new StringBuilder();
        sb.append("Cluster has %d namespaces (%d with 3scale, %d with Kuadrant).\n\n".formatted(
                projects.size(), threeScaleCount, kuadrantCount));

        List<ProjectInfo> relevant = projects.stream()
                .filter(p -> p.hasThreeScale() || p.hasKuadrant())
                .toList();
        if (!relevant.isEmpty()) {
            sb.append("Namespaces with API management resources:\n");
            relevant.forEach(p -> sb.append("- %s [%s%s] (status: %s)\n".formatted(
                    p.name(),
                    p.hasThreeScale() ? "3scale " : "",
                    p.hasKuadrant() ? "Kuadrant" : "",
                    p.status())));
        }
        return sb.toString();
    }

    @Tool("Get the connection status of the 3scale Admin API including product and backend counts")
    public String getThreeScaleStatus() {
        Map<String, Object> status = threeScaleService.getAdminApiStatus();
        StringBuilder sb = new StringBuilder();
        sb.append("3scale Admin API status:\n");
        sb.append("- Configured: ").append(status.get("configured")).append("\n");
        sb.append("- CRD Discovery Enabled: ").append(status.get("crdDiscoveryEnabled")).append("\n");
        if (Boolean.TRUE.equals(status.get("configured"))) {
            sb.append("- Reachable: ").append(status.get("reachable")).append("\n");
            if (Boolean.TRUE.equals(status.get("reachable"))) {
                sb.append("- Products via Admin API: ").append(status.get("productCount")).append("\n");
                sb.append("- Backend APIs: ").append(status.get("backendApiCount")).append("\n");
                sb.append("- Active Docs: ").append(status.get("activeDocsCount")).append("\n");
            } else {
                sb.append("- Error: ").append(status.getOrDefault("error", "unknown")).append("\n");
            }
        }
        return sb.toString();
    }

    @Tool("Show Kuadrant topology for a specific namespace using kuadrantctl")
    public String getKuadrantTopology(String namespace) {
        String result = kuadrantCtlService.topology(namespace);
        if (result.startsWith("ERROR")) {
            return "kuadrantctl topology failed for namespace '%s': %s".formatted(namespace, result);
        }
        return "Kuadrant topology for namespace '%s':\n%s".formatted(namespace, result);
    }

    @Tool("Get kuadrantctl version to verify it is installed and working")
    public String getKuadrantctlVersion() {
        return kuadrantCtlService.version();
    }
}

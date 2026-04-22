package io.gateforge.service;

import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.gateforge.model.ProjectInfo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class ClusterService {

    @Inject
    KubernetesClient kubernetesClient;

    private static final String[][] THREESCALE_CRDS = {
        {"capabilities.3scale.net", "v1beta1", "products"},
        {"capabilities.3scale.net", "v1beta1", "backends"},
        {"apps.3scale.net", "v1alpha1", "apimanagers"}
    };

    private static final String[][] KUADRANT_CRDS = {
        {"kuadrant.io", "v1", "authpolicies"},
        {"kuadrant.io", "v1", "ratelimitpolicies"},
        {"kuadrant.io", "v1", "kuadrants"}
    };

    public List<ProjectInfo> listProjects() {
        Set<String> threeScaleNs = collectNamespaces(THREESCALE_CRDS);
        Set<String> kuadrantNs = collectNamespaces(KUADRANT_CRDS);

        return kubernetesClient.namespaces().list().getItems().stream()
                .map(ns -> toProjectInfo(ns, threeScaleNs, kuadrantNs))
                .collect(Collectors.toList());
    }

    public ProjectInfo getProject(String name) {
        Namespace ns = kubernetesClient.namespaces().withName(name).get();
        if (ns == null) return null;
        Set<String> threeScaleNs = collectNamespaces(THREESCALE_CRDS);
        Set<String> kuadrantNs = collectNamespaces(KUADRANT_CRDS);
        return toProjectInfo(ns, threeScaleNs, kuadrantNs);
    }

    private ProjectInfo toProjectInfo(Namespace ns, Set<String> threeScaleNs, Set<String> kuadrantNs) {
        String name = ns.getMetadata().getName();
        String status = ns.getStatus() != null ? ns.getStatus().getPhase() : "Unknown";
        String created = ns.getMetadata().getCreationTimestamp();
        return new ProjectInfo(name, status, created, threeScaleNs.contains(name), kuadrantNs.contains(name));
    }

    private Set<String> collectNamespaces(String[][] crds) {
        Set<String> namespaces = new HashSet<>();
        for (String[] crd : crds) {
            try {
                CustomResourceDefinitionContext ctx = new CustomResourceDefinitionContext.Builder()
                        .withGroup(crd[0]).withVersion(crd[1]).withPlural(crd[2])
                        .withScope("Namespaced").build();
                List<GenericKubernetesResource> items = kubernetesClient.genericKubernetesResources(ctx)
                        .inAnyNamespace().list().getItems();
                if (items != null) {
                    items.forEach(r -> namespaces.add(r.getMetadata().getNamespace()));
                }
            } catch (Exception ignored) {
            }
        }
        return namespaces;
    }
}

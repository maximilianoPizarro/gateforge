package io.gateforge.service;

import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.gateforge.model.ProjectInfo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class ClusterService {

    @Inject
    KubernetesClient kubernetesClient;

    public List<ProjectInfo> listProjects() {
        return kubernetesClient.namespaces().list().getItems().stream()
                .map(this::toProjectInfo)
                .collect(Collectors.toList());
    }

    public ProjectInfo getProject(String name) {
        Namespace ns = kubernetesClient.namespaces().withName(name).get();
        return ns != null ? toProjectInfo(ns) : null;
    }

    private ProjectInfo toProjectInfo(Namespace ns) {
        String name = ns.getMetadata().getName();
        String status = ns.getStatus() != null ? ns.getStatus().getPhase() : "Unknown";
        String created = ns.getMetadata().getCreationTimestamp();
        boolean has3scale = hasResourceInNamespace(name, "capabilities.3scale.net", "v1beta1", "products")
                || hasResourceInNamespace(name, "capabilities.3scale.net", "v1beta1", "backends")
                || hasResourceInNamespace(name, "apps.3scale.net", "v1alpha1", "apimanagers");
        boolean hasKuadrant = hasResourceInNamespace(name, "kuadrant.io", "v1", "authpolicies")
                || hasResourceInNamespace(name, "kuadrant.io", "v1", "ratelimitpolicies")
                || hasResourceInNamespace(name, "kuadrant.io", "v1", "kuadrants");
        return new ProjectInfo(name, status, created, has3scale, hasKuadrant);
    }

    private boolean hasResourceInNamespace(String namespace, String group, String version, String plural) {
        try {
            CustomResourceDefinitionContext ctx = new CustomResourceDefinitionContext.Builder()
                    .withGroup(group)
                    .withVersion(version)
                    .withPlural(plural)
                    .withScope("Namespaced")
                    .build();
            List<GenericKubernetesResource> items = kubernetesClient.genericKubernetesResources(ctx)
                    .inNamespace(namespace).list().getItems();
            return items != null && !items.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}

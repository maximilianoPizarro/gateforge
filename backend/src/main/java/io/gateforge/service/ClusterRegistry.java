package io.gateforge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.gateforge.model.TargetCluster;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ClusterRegistry {

    private static final Logger LOG = Logger.getLogger(ClusterRegistry.class);

    @Inject
    KubernetesClient localClient;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "gateforge.target-clusters", defaultValue = "")
    String clustersJson;

    @ConfigProperty(name = "gateforge.argocd.cluster-discovery", defaultValue = "false")
    boolean argocdDiscovery;

    private final Map<String, KubernetesClient> clients = new ConcurrentHashMap<>();
    private final Map<String, TargetCluster> clusters = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        TargetCluster local = TargetCluster.local();
        clusters.put("local", local);
        clients.put("local", localClient);

        if (clustersJson != null && !clustersJson.isBlank()) {
            try {
                TargetCluster[] extras = objectMapper.readValue(clustersJson, TargetCluster[].class);
                for (TargetCluster tc : extras) {
                    if (tc.enabled()) addCluster(tc);
                }
            } catch (Exception e) {
                LOG.warn("Failed to parse TARGET_CLUSTERS JSON", e);
            }
        }

        if (argocdDiscovery) {
            discoverArgoCDClusters();
        }

        LOG.infof("ClusterRegistry initialized with %d cluster(s): %s", clusters.size(), clusters.keySet());
    }

    public void addCluster(TargetCluster cluster) {
        clusters.put(cluster.id(), cluster);
        try {
            KubernetesClient client = buildClient(cluster);
            clients.put(cluster.id(), client);
            LOG.infof("Registered target cluster: %s (%s)", cluster.id(), cluster.apiServerUrl());
        } catch (Exception e) {
            LOG.warnf("Failed to create client for cluster %s: %s", cluster.id(), e.getMessage());
        }
    }

    public void removeCluster(String id) {
        if ("local".equals(id)) return;
        clusters.remove(id);
        KubernetesClient removed = clients.remove(id);
        if (removed != null && removed != localClient) {
            removed.close();
        }
    }

    public KubernetesClient getClient(String clusterId) {
        if (clusterId == null || clusterId.isBlank() || "local".equals(clusterId)) {
            return localClient;
        }
        return clients.getOrDefault(clusterId, localClient);
    }

    public List<TargetCluster> listClusters() {
        return new ArrayList<>(clusters.values());
    }

    public TargetCluster getCluster(String id) {
        return clusters.get(id);
    }

    public Map<String, Object> validateAccess(String clusterId) {
        Map<String, Object> result = new LinkedHashMap<>();
        KubernetesClient client = clients.get(clusterId);
        TargetCluster cluster = clusters.get(clusterId);

        result.put("clusterId", clusterId);
        result.put("label", cluster != null ? cluster.label() : "Unknown");

        if (client == null) {
            result.put("connected", false);
            result.put("error", "No client registered");
            return result;
        }

        try {
            client.namespaces().list();
            result.put("connected", true);
            result.put("canListNamespaces", true);
        } catch (Exception e) {
            result.put("connected", false);
            result.put("error", e.getMessage());
            return result;
        }

        try {
            client.genericKubernetesResources(
                    new io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext.Builder()
                            .withGroup("gateway.networking.k8s.io")
                            .withVersion("v1")
                            .withPlural("gateways")
                            .withScope("Namespaced")
                            .build()
            ).inAnyNamespace().list();
            result.put("canManageGateways", true);
        } catch (Exception e) {
            result.put("canManageGateways", false);
        }

        return result;
    }

    private void discoverArgoCDClusters() {
        try {
            List<Secret> secrets = localClient.secrets()
                    .inNamespace("openshift-gitops")
                    .withLabel("argocd.argoproj.io/secret-type", "cluster")
                    .list().getItems();

            for (Secret secret : secrets) {
                Map<String, String> data = secret.getData();
                if (data == null) continue;

                String name = decodeBase64(data.get("name"));
                String server = decodeBase64(data.get("server"));
                String configJson = decodeBase64(data.get("config"));

                if (server == null || server.isBlank() || server.contains("kubernetes.default")) continue;

                String token = "";
                if (configJson != null) {
                    try {
                        var config = objectMapper.readTree(configJson);
                        if (config.has("bearerToken")) {
                            token = config.get("bearerToken").asText();
                        }
                    } catch (Exception ignored) {}
                }

                String id = "argocd-" + (name != null ? name : UUID.randomUUID().toString().substring(0, 6));
                TargetCluster cluster = new TargetCluster(
                        id, "ArgoCD: " + name, server, token, "argocd-secret", false, true);
                addCluster(cluster);
                LOG.infof("Discovered ArgoCD cluster: %s -> %s", name, server);
            }
        } catch (Exception e) {
            LOG.warn("ArgoCD cluster discovery failed (this is expected if no ArgoCD present)", e);
        }
    }

    private KubernetesClient buildClient(TargetCluster cluster) {
        Config config = new Config();
        config.setMasterUrl(cluster.apiServerUrl());
        config.setOauthToken(cluster.token());
        config.setTrustCerts(!cluster.verifySsl());
        config.setDisableHostnameVerification(!cluster.verifySsl());
        return new KubernetesClientBuilder().withConfig(config).build();
    }

    private String decodeBase64(String encoded) {
        if (encoded == null) return null;
        return new String(Base64.getDecoder().decode(encoded));
    }
}

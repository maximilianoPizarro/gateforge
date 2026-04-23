package io.gateforge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gateforge.model.ThreeScaleSource;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ThreeScaleSourceRegistry {

    private static final Logger LOG = Logger.getLogger(ThreeScaleSourceRegistry.class);

    @ConfigProperty(name = "gateforge.threescale.admin-url", defaultValue = "http://localhost")
    String defaultAdminUrl;

    @ConfigProperty(name = "gateforge.threescale.access-token", defaultValue = "none")
    String defaultAccessToken;

    @ConfigProperty(name = "gateforge.threescale.sources")
    Optional<String> sourcesJson;

    @Inject
    ObjectMapper objectMapper;

    private final Map<String, ThreeScaleAdminApiClient> clients = new ConcurrentHashMap<>();
    private final Map<String, ThreeScaleSource> sources = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        if (defaultAdminUrl != null && !"http://localhost".equals(defaultAdminUrl)
                && defaultAccessToken != null && !"none".equals(defaultAccessToken)) {
            ThreeScaleSource defaultSource = new ThreeScaleSource(
                    "default", "Default 3scale", defaultAdminUrl, defaultAccessToken, true);
            addSource(defaultSource);
        }

        if (sourcesJson.isPresent() && !sourcesJson.get().isBlank()) {
            try {
                ThreeScaleSource[] extraSources = objectMapper.readValue(sourcesJson.get(), ThreeScaleSource[].class);
                for (ThreeScaleSource src : extraSources) {
                    if (src.enabled()) addSource(src);
                }
            } catch (Exception e) {
                LOG.warn("Failed to parse THREESCALE_SOURCES JSON", e);
            }
        }

        LOG.infof("ThreeScaleSourceRegistry initialized with %d source(s): %s",
                sources.size(), sources.keySet());
    }

    public void addSource(ThreeScaleSource source) {
        sources.put(source.id(), source);
        clients.put(source.id(), new ThreeScaleAdminApiClient(
                source.id(), source.adminUrl(), source.accessToken(), objectMapper));
        LOG.infof("Registered 3scale source: %s (%s)", source.id(), source.adminUrl());
    }

    public void removeSource(String id) {
        sources.remove(id);
        clients.remove(id);
    }

    public ThreeScaleAdminApiClient getClient(String id) {
        return clients.get(id);
    }

    public Collection<ThreeScaleAdminApiClient> getAllClients() {
        return Collections.unmodifiableCollection(clients.values());
    }

    public ThreeScaleAdminApiClient getDefaultClient() {
        ThreeScaleAdminApiClient client = clients.get("default");
        if (client != null) return client;
        return clients.values().stream().findFirst().orElse(null);
    }

    public boolean hasConfiguredClients() {
        return clients.values().stream().anyMatch(ThreeScaleAdminApiClient::isConfigured);
    }

    public List<ThreeScaleSource> listSources() {
        return new ArrayList<>(sources.values());
    }

    public ThreeScaleSource getSource(String id) {
        return sources.get(id);
    }

    public Map<String, Object> getSourceStatus(String sourceId) {
        Map<String, Object> status = new LinkedHashMap<>();
        ThreeScaleAdminApiClient client = clients.get(sourceId);
        ThreeScaleSource source = sources.get(sourceId);

        if (client == null || source == null) {
            status.put("error", "Source not found");
            return status;
        }

        status.put("id", source.id());
        status.put("label", source.label());
        status.put("adminUrl", source.adminUrl());
        status.put("configured", client.isConfigured());
        status.put("enabled", source.enabled());

        if (client.isConfigured()) {
            try {
                List<Map<String, Object>> services = client.listServices();
                status.put("reachable", true);
                status.put("productCount", services.size());
            } catch (Exception e) {
                status.put("reachable", false);
                status.put("error", e.getMessage());
            }
        }

        return status;
    }
}

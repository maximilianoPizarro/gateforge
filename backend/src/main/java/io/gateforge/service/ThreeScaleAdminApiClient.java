package io.gateforge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class ThreeScaleAdminApiClient {

    private static final Logger LOG = Logger.getLogger(ThreeScaleAdminApiClient.class.getName());

    @ConfigProperty(name = "gateforge.threescale.admin-url", defaultValue = "http://localhost")
    String adminUrl;

    @ConfigProperty(name = "gateforge.threescale.access-token", defaultValue = "none")
    String accessToken;

    @Inject
    ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public boolean isConfigured() {
        return adminUrl != null
                && !adminUrl.isBlank()
                && !"http://localhost".equals(adminUrl)
                && accessToken != null
                && !accessToken.isBlank()
                && !"none".equals(accessToken);
    }

    public List<Map<String, Object>> listServices() {
        return fetchPaginatedList("/admin/api/services.json", "services", "service");
    }

    public List<Map<String, Object>> listBackendApis() {
        return fetchPaginatedList("/admin/api/backend_apis.json", "backend_apis", "backend_api");
    }

    public List<Map<String, Object>> listActiveDocs() {
        return fetchPaginatedList("/admin/api/active_docs.json", "api_docs", "api_doc");
    }

    public List<Map<String, Object>> listMappingRules(long serviceId) {
        return fetchPaginatedList(
                "/admin/api/services/" + serviceId + "/proxy/mapping_rules.json",
                "mapping_rules", "mapping_rule");
    }

    public List<Map<String, Object>> listBackendUsages(long serviceId) {
        return fetchPaginatedList(
                "/admin/api/services/" + serviceId + "/backend_usages.json",
                "backend_usages", "backend_usage");
    }

    public Map<String, Object> getServiceProxy(long serviceId) {
        try {
            String url = buildUrl("/admin/api/services/" + serviceId + "/proxy.json", 1, 1);
            JsonNode root = doGet(url);
            if (root != null && root.has("proxy")) {
                return objectMapper.convertValue(root.get("proxy"), Map.class);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to get proxy for service " + serviceId, e);
        }
        return Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchPaginatedList(String path, String collectionKey, String itemKey) {
        List<Map<String, Object>> result = new ArrayList<>();
        int page = 1;
        int perPage = 100;

        while (true) {
            try {
                String url = buildUrl(path, page, perPage);
                JsonNode root = doGet(url);
                if (root == null) break;

                JsonNode collection = root.has(collectionKey) ? root.get(collectionKey) : root;
                if (!collection.isArray() || collection.isEmpty()) break;

                for (JsonNode node : collection) {
                    JsonNode item = node.has(itemKey) ? node.get(itemKey) : node;
                    result.add(objectMapper.convertValue(item, Map.class));
                }

                if (collection.size() < perPage) break;
                page++;
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Error fetching " + path + " page " + page, e);
                break;
            }
        }
        return result;
    }

    private JsonNode doGet(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            LOG.warning("3scale Admin API returned " + response.statusCode() + " for " + url);
            return null;
        }
        return objectMapper.readTree(response.body());
    }

    private String buildUrl(String path, int page, int perPage) {
        String base = adminUrl.endsWith("/") ? adminUrl.substring(0, adminUrl.length() - 1) : adminUrl;
        String sep = path.contains("?") ? "&" : "?";
        return base + path + sep + "access_token=" + accessToken + "&page=" + page + "&per_page=" + perPage;
    }
}

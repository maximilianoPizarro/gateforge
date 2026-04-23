package io.gateforge.resource;

import io.gateforge.ai.GateForgeTools;
import io.gateforge.ai.MigrationAgent;
import io.gateforge.model.ChatMessage;
import io.gateforge.service.ThreeScaleService;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import io.quarkus.runtime.StartupEvent;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Path("/api/chat")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ChatResource {

    private static final Logger LOG = Logger.getLogger(ChatResource.class);
    private static final String FAQ_CACHE = "gateforge-faq";

    private static final String[] FAQ_PROMPTS = {
            "List all 3scale Products in my cluster",
            "Analyze my 3scale config and create a migration plan",
            "Generate an AuthPolicy for API Key authentication",
            "Create a RateLimitPolicy for 100 req/min",
            "Compare shared vs dedicated gateway strategies",
            "Show kuadrantctl topology",
            "What is Connectivity Link?",
            "How does GateForge migrate from 3scale to Kuadrant?",
            "What is the difference between AuthPolicy and RateLimitPolicy?",
            "How to configure OIDC authentication with Kuadrant?"
    };

    @Inject
    MigrationAgent migrationAgent;

    @Inject
    GateForgeTools tools;

    @Inject
    ThreeScaleService threeScaleService;

    @Inject
    RemoteCacheManager cacheManager;

    void onStartup(@Observes StartupEvent ev) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            try {
                Thread.sleep(15000);
                LOG.info("Starting FAQ cache warm-up...");
                RemoteCache<String, String> cache = cacheManager.getCache(FAQ_CACHE);
                if (cache == null) {
                    LOG.warn("FAQ cache not available, skipping warm-up");
                    return;
                }
                int loaded = 0;
                for (String prompt : FAQ_PROMPTS) {
                    String key = prompt.trim().toLowerCase();
                    if (cache.get(key) != null) {
                        loaded++;
                        continue;
                    }
                    try {
                        String contextEnriched = buildContextMessage(prompt);
                        String response = migrationAgent.chat(contextEnriched);
                        response = cleanThinkingBlocks(response);
                        if (response != null && !response.isBlank()) {
                            cache.put(key, response, 24, TimeUnit.HOURS);
                            loaded++;
                            LOG.infof("FAQ cached [%d/%d]: %s", loaded, FAQ_PROMPTS.length, prompt);
                        }
                    } catch (Exception e) {
                        LOG.warnf("FAQ cache warm-up failed for: %s — %s", prompt, e.getMessage());
                    }
                }
                LOG.infof("FAQ cache warm-up complete: %d/%d entries", loaded, FAQ_PROMPTS.length);
            } catch (Exception e) {
                LOG.warn("FAQ cache warm-up interrupted", e);
            }
        });
        executor.shutdown();
    }

    @POST
    public Response chat(ChatMessage userMessage) {
        try {
            String normalized = userMessage.content().trim().toLowerCase();
            RemoteCache<String, String> faqCache = null;
            try {
                faqCache = cacheManager.getCache(FAQ_CACHE);
            } catch (Exception e) {
                LOG.debug("FAQ cache not available");
            }

            if (faqCache != null) {
                String cached = faqCache.get(normalized);
                if (cached != null) {
                    LOG.infof("FAQ cache hit for: %s", userMessage.content());
                    return Response.ok(new ChatMessage("assistant", cached, true)).build();
                }
            }

            String contextEnriched = buildContextMessage(userMessage.content());
            String response = migrationAgent.chat(contextEnriched);
            response = cleanThinkingBlocks(response);
            return Response.ok(new ChatMessage("assistant", response, false)).build();
        } catch (Exception e) {
            LOG.error("AI chat failed", e);
            String errorMsg = extractUserFriendlyError(e);
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(new ChatMessage("error", errorMsg))
                    .build();
        }
    }

    @POST
    @Path("/warm-faq")
    public Response warmFaqCache() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            try {
                RemoteCache<String, String> cache = cacheManager.getCache(FAQ_CACHE);
                if (cache == null) return;
                for (String prompt : FAQ_PROMPTS) {
                    try {
                        String contextEnriched = buildContextMessage(prompt);
                        String response = migrationAgent.chat(contextEnriched);
                        response = cleanThinkingBlocks(response);
                        if (response != null && !response.isBlank()) {
                            cache.put(prompt.trim().toLowerCase(), response, 24, TimeUnit.HOURS);
                        }
                    } catch (Exception e) {
                        LOG.warnf("FAQ refresh failed for: %s", prompt);
                    }
                }
                LOG.info("FAQ cache refresh complete");
            } catch (Exception e) {
                LOG.warn("FAQ cache refresh failed", e);
            }
        });
        executor.shutdown();
        return Response.ok(Map.of("status", "warming", "count", FAQ_PROMPTS.length)).build();
    }

    @GET
    @Path("/faq-status")
    public Response faqStatus() {
        try {
            RemoteCache<String, String> cache = cacheManager.getCache(FAQ_CACHE);
            int cached = 0;
            if (cache != null) {
                for (String prompt : FAQ_PROMPTS) {
                    if (cache.get(prompt.trim().toLowerCase()) != null) cached++;
                }
            }
            return Response.ok(Map.of("total", FAQ_PROMPTS.length, "cached", cached)).build();
        } catch (Exception e) {
            return Response.ok(Map.of("total", FAQ_PROMPTS.length, "cached", 0, "error", e.getMessage())).build();
        }
    }

    private String extractUserFriendlyError(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";
        Throwable cause = e.getCause();
        String causeMsg = cause != null && cause.getMessage() != null ? cause.getMessage() : "";

        if (msg.contains("401") || causeMsg.contains("401") || causeMsg.contains("auth_error")) {
            return "AI service authentication failed. The API key is invalid or not configured. Please contact the administrator.";
        }
        if (msg.contains("timeout") || msg.contains("Timeout") || causeMsg.contains("timeout")) {
            return "AI service timed out. The model is taking too long to respond. Please try again with a simpler question.";
        }
        if (msg.contains("ContextWindowExceeded") || causeMsg.contains("ContextWindowExceeded")) {
            return "The question context is too large for the AI model. Please ask about a specific product or topic.";
        }
        if (msg.contains("Connection refused") || causeMsg.contains("Connection refused")) {
            return "Cannot reach the AI service. Please verify the AI endpoint configuration.";
        }
        return "AI service is temporarily unavailable. Please try again later. (" + (causeMsg.isEmpty() ? msg : causeMsg) + ")";
    }

    private String cleanThinkingBlocks(String text) {
        if (text == null) return "";
        String cleaned = text.replaceAll("(?s)<think>.*?</think>\\s*", "");
        int closeIdx = cleaned.indexOf("</think>");
        if (closeIdx >= 0) {
            cleaned = cleaned.substring(closeIdx + "</think>".length());
        }
        return cleaned.trim();
    }

    @GET
    @Path("/status")
    public ChatMessage status() {
        return new ChatMessage("system", "GateForge AI chat is active");
    }

    private static final int MAX_CONTEXT_PRODUCTS = 20;

    private String buildContextMessage(String userQuestion) {
        StringBuilder ctx = new StringBuilder();
        ctx.append("## Current Cluster State\n\n");

        try {
            ctx.append("### 3scale Status\n").append(tools.getThreeScaleStatus()).append("\n\n");
        } catch (Exception e) {
            LOG.debug("Failed to fetch 3scale status for context", e);
        }

        try {
            ctx.append("### 3scale Products (summary)\n").append(buildProductSummary(userQuestion)).append("\n\n");
        } catch (Exception e) {
            LOG.debug("Failed to fetch products for context", e);
        }

        try {
            ctx.append("### OpenShift Projects\n").append(tools.listProjects()).append("\n\n");
        } catch (Exception e) {
            LOG.debug("Failed to fetch projects for context", e);
        }

        try {
            ctx.append("### kuadrantctl\n").append(tools.getKuadrantctlVersion()).append("\n\n");
        } catch (Exception e) {
            LOG.debug("Failed to fetch kuadrantctl version for context", e);
        }

        ctx.append("---\n\n## User Question\n").append(userQuestion);
        return ctx.toString();
    }

    private String buildProductSummary(String userQuestion) {
        var products = threeScaleService.listProducts();
        if (products.isEmpty()) {
            return "No 3scale products found.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Total products: %d\n".formatted(products.size()));

        String q = userQuestion != null ? userQuestion.toLowerCase() : "";
        var relevant = products.stream()
                .filter(p -> q.isEmpty()
                        || q.contains(p.name().toLowerCase())
                        || q.contains(p.systemName().toLowerCase())
                        || (p.namespace() != null && q.contains(p.namespace().toLowerCase())))
                .limit(MAX_CONTEXT_PRODUCTS)
                .toList();

        if (relevant.isEmpty() || relevant.size() == products.size()) {
            relevant = products.stream().limit(MAX_CONTEXT_PRODUCTS).toList();
        }

        sb.append("Showing %d relevant products:\n".formatted(relevant.size()));
        relevant.forEach(p -> sb.append("- %s (ns: %s, source: %s, %d rules, %d backends)\n".formatted(
                p.name(), p.namespace(), p.source(),
                p.mappingRules().size(), p.backendUsages().size())));

        if (products.size() > relevant.size()) {
            sb.append("... and %d more. Ask about a specific product by name for details.\n".formatted(
                    products.size() - relevant.size()));
        }
        return sb.toString();
    }
}

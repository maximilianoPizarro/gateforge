package io.gateforge.resource;

import io.gateforge.ai.GateForgeTools;
import io.gateforge.ai.MigrationAgent;
import io.gateforge.model.ChatMessage;
import io.gateforge.service.ThreeScaleService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

@Path("/api/chat")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ChatResource {

    private static final Logger LOG = Logger.getLogger(ChatResource.class);

    @Inject
    MigrationAgent migrationAgent;

    @Inject
    GateForgeTools tools;

    @Inject
    ThreeScaleService threeScaleService;

    @POST
    public Response chat(ChatMessage userMessage) {
        try {
            String contextEnriched = buildContextMessage(userMessage.content());
            String response = migrationAgent.chat(contextEnriched);
            response = cleanThinkingBlocks(response);
            return Response.ok(new ChatMessage("assistant", response)).build();
        } catch (Exception e) {
            LOG.error("AI chat failed", e);
            String errorMsg = extractUserFriendlyError(e);
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(new ChatMessage("error", errorMsg))
                    .build();
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

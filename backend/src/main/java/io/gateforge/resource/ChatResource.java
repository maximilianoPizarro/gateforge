package io.gateforge.resource;

import io.gateforge.ai.GateForgeTools;
import io.gateforge.ai.MigrationAgent;
import io.gateforge.model.ChatMessage;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
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

    @POST
    public ChatMessage chat(ChatMessage userMessage) {
        String contextEnriched = buildContextMessage(userMessage.content());
        String response = migrationAgent.chat(contextEnriched);
        response = cleanThinkingBlocks(response);
        return new ChatMessage("assistant", response);
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

    private String buildContextMessage(String userQuestion) {
        StringBuilder ctx = new StringBuilder();
        ctx.append("## Current Cluster State\n\n");

        try {
            ctx.append("### 3scale Status\n").append(tools.getThreeScaleStatus()).append("\n\n");
        } catch (Exception e) {
            LOG.debug("Failed to fetch 3scale status for context", e);
        }

        try {
            ctx.append("### 3scale Products\n").append(tools.listThreeScaleProducts()).append("\n\n");
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
}

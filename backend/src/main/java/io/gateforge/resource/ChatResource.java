package io.gateforge.resource;

import io.gateforge.ai.MigrationAgent;
import io.gateforge.model.ChatMessage;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

@Path("/api/chat")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ChatResource {

    @Inject
    MigrationAgent migrationAgent;

    @POST
    public ChatMessage chat(ChatMessage userMessage) {
        String response = migrationAgent.chat(userMessage.content());
        return new ChatMessage("assistant", response);
    }

    @GET
    @Path("/status")
    public ChatMessage status() {
        return new ChatMessage("system", "GateForge AI chat is active");
    }
}

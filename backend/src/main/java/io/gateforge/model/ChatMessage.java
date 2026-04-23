package io.gateforge.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessage(String role, String content, Boolean cached) {
    public ChatMessage(String role, String content) {
        this(role, content, null);
    }
}

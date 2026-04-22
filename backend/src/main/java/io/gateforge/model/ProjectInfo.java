package io.gateforge.model;

public record ProjectInfo(
    String name,
    String status,
    String creationTimestamp,
    boolean hasThreeScale,
    boolean hasKuadrant
) {}

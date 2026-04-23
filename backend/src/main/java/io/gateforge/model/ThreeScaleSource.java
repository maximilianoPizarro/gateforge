package io.gateforge.model;

public record ThreeScaleSource(
    String id,
    String label,
    String adminUrl,
    String accessToken,
    boolean enabled
) {
    public ThreeScaleSource withEnabled(boolean enabled) {
        return new ThreeScaleSource(id, label, adminUrl, accessToken, enabled);
    }
}

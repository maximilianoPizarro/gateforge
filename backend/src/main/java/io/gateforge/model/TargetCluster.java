package io.gateforge.model;

public record TargetCluster(
    String id,
    String label,
    String apiServerUrl,
    String token,
    String authType,
    boolean verifySsl,
    boolean enabled
) {
    public static TargetCluster local() {
        return new TargetCluster("local", "Local (in-cluster)", "", "", "in-cluster", true, true);
    }
}

package io.gateforge.service;

import io.gateforge.model.APICastConfig;
import io.gateforge.model.APICastConfig.*;
import io.gateforge.model.MigrationPlan;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class APICastToIstioMapper {

    private static final Logger LOG = Logger.getLogger(APICastToIstioMapper.class);

    @ConfigProperty(name = "gateforge.cluster-domain", defaultValue = "apps.cluster.example.com")
    String clusterDomain;

    @ConfigProperty(name = "gateforge.connectivity-link.gateway-class-name", defaultValue = "istio")
    String gatewayClassName;

    public List<MigrationPlan.GeneratedResource> mapAPICastToIstio(APICastConfig config) {
        LOG.infof("Mapping APIcast '%s' to Istio Gateway resources...", config.apiManagerName());

        List<MigrationPlan.GeneratedResource> resources = new ArrayList<>();
        String ns = config.namespace();
        String name = config.apiManagerName();

        if (config.stagingSpec() != null) {
            resources.add(generateGateway(name, ns, "staging", config.stagingSpec()));
        }
        if (config.productionSpec() != null) {
            resources.add(generateGateway(name, ns, "production", config.productionSpec()));
        }

        if (config.customPolicies() != null) {
            for (CustomPolicy policy : config.customPolicies()) {
                resources.add(generateEnvoyFilterForPolicy(name, ns, policy));
            }
        }

        if (config.tls() != null && config.tls().verify()) {
            resources.add(generateDestinationRule(name, ns));
        }

        if (config.openTracing() != null && config.openTracing().enabled()) {
            resources.add(generateTelemetryPolicy(name, ns));
        }

        if (config.service() != null) {
            resources.add(generateServiceEntry(name, ns, config.service()));
        }

        LOG.infof("Generated %d resources for '%s'", resources.size(), name);
        return resources;
    }

    public List<List<MigrationPlan.GeneratedResource>> mapMultipleAPICasts(List<APICastConfig> configs) {
        LOG.infof("Batch mapping %d APIManagers...", configs.size());
        return configs.stream().map(this::mapAPICastToIstio).toList();
    }

    private MigrationPlan.GeneratedResource generateGateway(String apiManagerName, String ns,
            String environment, APICastDeploymentSpec spec) {
        String yaml = """
                apiVersion: gateway.networking.k8s.io/v1
                kind: Gateway
                metadata:
                  name: %s-%s-gateway
                  namespace: %s
                  labels:
                    app.kubernetes.io/managed-by: gateforge
                    gateforge.io/source: apicast
                  annotations:
                    gateforge.io/original-replicas: "%d"
                spec:
                  gatewayClassName: %s
                  listeners:
                    - name: http
                      port: 80
                      protocol: HTTP
                      hostname: "*.%s"
                      allowedRoutes:
                        namespaces:
                          from: All
                """.formatted(apiManagerName, environment, ns,
                spec.replicas(), gatewayClassName, clusterDomain);
        return new MigrationPlan.GeneratedResource("Gateway",
                apiManagerName + "-" + environment + "-gateway", ns, yaml);
    }

    private MigrationPlan.GeneratedResource generateEnvoyFilterForPolicy(String apiManagerName,
            String ns, CustomPolicy policy) {
        String yaml = """
                apiVersion: networking.istio.io/v1alpha3
                kind: EnvoyFilter
                metadata:
                  name: %s-%s
                  namespace: %s
                  labels:
                    app.kubernetes.io/managed-by: gateforge
                    gateforge.io/source: apicast
                spec:
                  workloadSelector:
                    labels:
                      app: %s-gateway
                  configPatches:
                    - applyTo: HTTP_FILTER
                      match:
                        context: GATEWAY
                      patch:
                        operation: INSERT_BEFORE
                        value:
                          name: %s
                          typed_config:
                            "@type": type.googleapis.com/envoy.extensions.filters.http.lua.v3.Lua
                            inlineCode: |
                              -- Policy: %s (migrated from APIcast by GateForge)
                              -- Original secret: %s
                """.formatted(apiManagerName, policy.name(), ns,
                apiManagerName, policy.name(), policy.name(),
                policy.secretRef() != null ? policy.secretRef() : "N/A");
        return new MigrationPlan.GeneratedResource("EnvoyFilter",
                apiManagerName + "-" + policy.name(), ns, yaml);
    }

    private MigrationPlan.GeneratedResource generateDestinationRule(String apiManagerName, String ns) {
        String yaml = """
                apiVersion: networking.istio.io/v1beta1
                kind: DestinationRule
                metadata:
                  name: %s-tls
                  namespace: %s
                  labels:
                    app.kubernetes.io/managed-by: gateforge
                    gateforge.io/source: apicast
                spec:
                  host: "*"
                  trafficPolicy:
                    tls:
                      mode: SIMPLE
                      caCertificates: /etc/ssl/certs/ca-certificates.crt
                """.formatted(apiManagerName, ns);
        return new MigrationPlan.GeneratedResource("DestinationRule",
                apiManagerName + "-tls", ns, yaml);
    }

    private MigrationPlan.GeneratedResource generateTelemetryPolicy(String apiManagerName, String ns) {
        String yaml = """
                apiVersion: telemetry.istio.io/v1alpha1
                kind: Telemetry
                metadata:
                  name: %s-telemetry
                  namespace: %s
                  labels:
                    app.kubernetes.io/managed-by: gateforge
                    gateforge.io/source: apicast
                spec:
                  tracing:
                    - providers:
                        - name: otel
                      randomSamplingPercentage: 100.0
                """.formatted(apiManagerName, ns);
        return new MigrationPlan.GeneratedResource("Telemetry",
                apiManagerName + "-telemetry", ns, yaml);
    }

    private MigrationPlan.GeneratedResource generateServiceEntry(String apiManagerName,
            String ns, ServiceExposureConfig svc) {
        String yaml = """
                apiVersion: networking.istio.io/v1beta1
                kind: ServiceEntry
                metadata:
                  name: %s-external
                  namespace: %s
                  labels:
                    app.kubernetes.io/managed-by: gateforge
                    gateforge.io/source: apicast
                spec:
                  hosts:
                    - "*.%s.svc.cluster.local"
                  ports:
                    - number: %d
                      name: http
                      protocol: HTTP
                  resolution: DNS
                """.formatted(apiManagerName, ns, ns, svc.port());
        return new MigrationPlan.GeneratedResource("ServiceEntry",
                apiManagerName + "-external", ns, yaml);
    }
}

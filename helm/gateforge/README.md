# GateForge Helm Chart

<p align="center">
  <img src="https://maximilianopizarro.github.io/gateforge/assets/logo.svg" alt="GateForge Logo" width="100">
</p>

AI-powered migration platform for transitioning from **Red Hat 3scale API Management** to **Red Hat Connectivity Link** (Kuadrant) on OpenShift 4.21.

## Prerequisites

- OpenShift 4.21+ with cluster-admin access
- 3scale Operator installed (for CRD discovery)
- Kuadrant Operator / Connectivity Link installed
- Helm 3

## Installation

```bash
helm repo add gateforge https://maximilianopizarro.github.io/gateforge/
helm repo update
helm install gateforge gateforge/gateforge
```

### With custom configuration

```bash
helm install gateforge gateforge/gateforge \
  --set ai.apiKey=YOUR_LLM_KEY \
  --set threescale.adminApi.url=https://3scale-admin.apps.example.com \
  --set threescale.adminApi.accessToken=YOUR_3SCALE_TOKEN \
  --set connectivityLink.gatewayStrategy=shared
```

## Architecture

| Layer | Technology | Description |
|-------|-----------|-------------|
| **Frontend** | Angular 18, @rhds/elements | SPA with Red Hat Design System, served by Nginx (UBI9) |
| **Backend** | Quarkus 3.x, Java 17 | REST API, AI agent, MCP servers, kuadrantctl integration |
| **AI** | LangChain4j, deepseek-r1-distill-qwen-14b | Migration analysis and chat assistant via LiteLLM |
| **MCP Servers** | 3scale, Connectivity Link, Kubernetes | Tool calling for AI agent via Model Context Protocol |
| **Migration** | kuadrantctl, Fabric8 K8s Client | Generate HTTPRoute, AuthPolicy, RateLimitPolicy |

## 3scale to Connectivity Link Mapping

| 3scale Object | Connectivity Link Resource |
|--------------|---------------------------|
| Product | Gateway + HTTPRoute + AuthPolicy + RateLimitPolicy |
| Backend | Service/Endpoints (HTTPRoute backendRefs) |
| Mapping Rules | HTTPRoute rules (path, method matches) |
| Application Plans (limits) | RateLimitPolicy (rates, counters) |
| Auth (OAuth2, API Key) | AuthPolicy (via Authorino/Keycloak) |
| Proxy/Gateway config | Gateway listener configuration |
| DNS/TLS settings | DNSPolicy + TLSPolicy |

## Gateway Strategies

| Strategy | Description |
|----------|-------------|
| `shared` | Single Gateway for all migrated applications (simplest) |
| `dual` | Two Gateways: internal services + external-facing APIs |
| `dedicated` | One Gateway per application (maximum isolation) |

## Values

| Value | Default | Description |
|-------|---------|-------------|
| `backend.image.repository` | `quay.io/maximilianopizarro/gateforge-backend` | Backend image |
| `backend.image.tag` | `v0.1.9` | Backend image tag |
| `frontend.image.repository` | `quay.io/maximilianopizarro/gateforge-frontend` | Frontend image |
| `frontend.image.tag` | `v0.1.9` | Frontend image tag |
| `ai.enabled` | `true` | Enable AI features |
| `ai.endpoint` | `https://litellm-prod.apps.maas.redhatworkshops.io/v1` | LLM endpoint URL |
| `ai.model` | `deepseek-r1-distill-qwen-14b` | AI model name |
| `ai.apiKey` | `""` | LLM API key |
| `threescale.adminApi.enabled` | `true` | Enable 3scale Admin API |
| `threescale.adminApi.url` | `""` | 3scale Admin Portal URL |
| `threescale.adminApi.accessToken` | `""` | 3scale provider access token |
| `threescale.crdDiscovery.enabled` | `true` | Auto-discover 3scale CRDs in cluster |
| `connectivityLink.targetNamespace` | `kuadrant-system` | Target namespace for Kuadrant resources |
| `connectivityLink.gatewayStrategy` | `shared` | Gateway strategy: shared / dual / dedicated |
| `connectivityLink.gatewayClassName` | `istio` | Gateway class name |
| `rbac.clusterAdmin` | `true` | Bind cluster-admin role to backend SA |
| `route.enabled` | `true` | Create OpenShift Route |
| `route.host` | `""` | Route hostname (auto-generated if empty) |
| `route.tls.termination` | `edge` | TLS termination type |

## API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/cluster/projects` | GET | List all cluster projects (cluster-admin) |
| `/api/threescale/products` | GET | List 3scale Product CRDs |
| `/api/threescale/backends` | GET | List 3scale Backend CRDs |
| `/api/migration/analyze` | POST | Analyze and plan migration |
| `/api/migration/plans` | GET | List migration plans |
| `/api/audit/reports` | GET | View audit log |
| `/api/chat` | POST | AI migration assistant |
| `/q/health/ready` | GET | Readiness probe |
| `/q/health/live` | GET | Liveness probe |

## Container Images

| Image | Description |
|-------|-------------|
| `quay.io/maximilianopizarro/gateforge-backend` | Quarkus backend with kuadrantctl (UBI9 OpenJDK 17) |
| `quay.io/maximilianopizarro/gateforge-frontend` | Angular frontend with Nginx (UBI9 Nginx 124) |

## Official Documentation

- [Red Hat Connectivity Link](https://docs.redhat.com/en/documentation/red_hat_connectivity_link)
- [Kuadrant Docs](https://docs.kuadrant.io/)
- [kuadrantctl](https://github.com/Kuadrant/kuadrantctl)
- [3scale API Management](https://docs.redhat.com/en/documentation/red_hat_3scale_api_management)
- [3scale Operator CRDs](https://github.com/3scale/3scale-operator)
- [Gateway API](https://gateway-api.sigs.k8s.io/)
- [Quarkus LangChain4j + MCP](https://quarkus.io/blog/quarkus-langchain4j-mcp/)
- [Red Hat Design System](https://ux.redhat.com/)
- [Migration Guide (ONLU)](https://onlu.ch/en/migration-path-from-red-hat-3scale-api-management-to-red-hat-connectivity-link/)

## License

Apache 2.0

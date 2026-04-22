<p align="center">
  <img src="docs/assets/logo.svg" alt="GateForge Logo" width="120">
</p>

# GateForge - 3scale to Connectivity Link Migration

[![Artifact Hub](https://img.shields.io/endpoint?url=https://artifacthub.io/badge/repository/gateforge)](https://artifacthub.io/packages/search?repo=gateforge)
[![Quay.io Backend](https://img.shields.io/badge/quay.io-backend-blue)](https://quay.io/repository/maximilianopizarro/gateforge-backend)
[![Quay.io Frontend](https://img.shields.io/badge/quay.io-frontend-blue)](https://quay.io/repository/maximilianopizarro/gateforge-frontend)
[![OpenShift](https://img.shields.io/badge/OpenShift-4.21-red)](https://docs.openshift.com/)
[![Documentation](https://img.shields.io/badge/docs-GitHub%20Pages-blue)](https://maximilianopizarro.github.io/gateforge/)

AI-powered migration platform for transitioning from **Red Hat 3scale API Management** to **Red Hat Connectivity Link** (Kuadrant) on OpenShift. Built with **Quarkus** (backend) and **Angular** with [Red Hat Design System](https://ux.redhat.com/) (frontend).

### Video Demo

> Coming soon

---

## Architecture Overview

| Layer | Technology | Description |
|-------|-----------|-------------|
| **Frontend** | Angular 18, @rhds/elements | SPA with RHDS web components, served by Nginx (UBI9) |
| **Backend** | Quarkus 3.x, Java 17 | REST API, AI agent, MCP servers, kuadrantctl integration |
| **AI** | LangChain4j, deepseek-r1-distill-qwen-14b | Migration analysis, resource generation, chat assistant |
| **MCP Servers** | 3scale, Connectivity Link, Kubernetes | Tool calling for AI agent via Model Context Protocol |
| **Migration** | kuadrantctl, Fabric8 K8s Client | Generate HTTPRoute, AuthPolicy, RateLimitPolicy from 3scale configs |
| **Packaging** | Helm Chart, Podman Compose | OpenShift deployment + local development |

**Containers:** Backend uses `registry.access.redhat.com/ubi9/openjdk-17`. Frontend uses `registry.access.redhat.com/ubi9/nginx-124`.

---

## Prerequisites

* **OpenShift 4.21** with cluster-admin access
* **3scale Operator** installed (for CRD discovery)
* **Kuadrant Operator** / Connectivity Link installed
* **Podman** (and optionally **podman-compose**) for local development
* **Java 17** + **Maven 3.9+** for backend development
* **Node.js 20** for frontend development
* **Helm 3** for deployment

---

## Running the Solution

### Local Development

**Backend:**

```bash
cd backend
mvn quarkus:dev
```

**Frontend:**

```bash
cd frontend
npm install
npm start
```

Open **http://localhost:4200**. The Angular dev server proxies `/api` to `http://localhost:8080`.

### Containers (Podman Compose)

```bash
podman-compose up -d --build
```

* **Frontend:** http://localhost:4200
* **Backend API:** http://localhost:8080/api
* **Health:** http://localhost:8080/q/health

### Helm Chart (OpenShift)

```bash
helm repo add gateforge https://maximilianopizarro.github.io/gateforge/
helm install gateforge gateforge/gateforge \
  --set ai.apiKey=YOUR_KEY \
  --set threescale.adminApi.url=https://3scale-admin.apps.example.com \
  --set threescale.adminApi.accessToken=YOUR_TOKEN
```

---

## API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| /api/cluster/projects | GET | List all cluster projects (cluster-admin) |
| /api/threescale/products | GET | List 3scale Product CRDs |
| /api/threescale/backends | GET | List 3scale Backend CRDs |
| /api/migration/analyze | POST | Analyze and plan migration |
| /api/migration/plans | GET | List migration plans |
| /api/audit/reports | GET | View audit log |
| /api/chat | POST | AI migration assistant |
| /api/chat/status | GET | Chat status |
| /q/health/ready | GET | Readiness probe |
| /q/health/live | GET | Liveness probe |

---

## Helm Chart Values

| Value | Default | Description |
|-------|---------|-------------|
| backend.image.tag | latest | Backend image tag |
| frontend.image.tag | latest | Frontend image tag |
| ai.enabled | true | Enable AI features |
| ai.endpoint | litellm-prod...  | LLM endpoint URL |
| ai.model | deepseek-r1-distill-qwen-14b | AI model name |
| ai.apiKey | "" | LLM API key |
| threescale.adminApi.url | "" | 3scale Admin Portal URL |
| threescale.adminApi.accessToken | "" | 3scale access token |
| connectivityLink.gatewayStrategy | shared | shared / dual / dedicated |
| connectivityLink.gatewayClassName | istio | Gateway class |
| rbac.clusterAdmin | true | Bind cluster-admin role |
| route.enabled | true | Create OpenShift Route |

---

## Official Documentation

* [Red Hat Connectivity Link](https://docs.redhat.com/en/documentation/red_hat_connectivity_link)
* [Kuadrant Docs](https://docs.kuadrant.io/)
* [kuadrantctl](https://github.com/Kuadrant/kuadrantctl)
* [3scale API Management](https://docs.redhat.com/en/documentation/red_hat_3scale_api_management)
* [3scale Operator](https://github.com/3scale/3scale-operator)
* [Gateway API](https://gateway-api.sigs.k8s.io/)
* [Quarkus LangChain4j + MCP](https://quarkus.io/blog/quarkus-langchain4j-mcp/)
* [Red Hat Design System](https://ux.redhat.com/)
* [Migration Guide (ONLU)](https://onlu.ch/en/migration-path-from-red-hat-3scale-api-management-to-red-hat-connectivity-link/)

---

## License

This software is licensed under the [Apache 2.0 license](LICENSE).

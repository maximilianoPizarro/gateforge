import express, { Router, Request, Response } from 'express';
import { LoggerService } from '@backstage/backend-plugin-api';
import { GateForgeKuadrantProcessor, MigrationEvent } from './processor';

export interface RouterOptions {
  logger: LoggerService;
  processor: GateForgeKuadrantProcessor;
}

export function createRouter(options: RouterOptions): Router {
  const { logger, processor } = options;
  const router = Router();
  router.use(express.json());

  router.post('/migration-event', (req: Request, res: Response) => {
    const body = req.body as {
      products?: unknown[];
      namespace?: string;
      planId?: string;
      resources?: { kind: string; name: string; namespace: string }[];
    };
    if (
      !body?.products ||
      !Array.isArray(body.products) ||
      body.products.length === 0 ||
      typeof body.planId !== 'string' ||
      !body.planId ||
      !body?.resources ||
      !Array.isArray(body.resources)
    ) {
      res.status(400).json({
        error:
          'Expected JSON body: { products: string[] | {id,namespace}[], namespace?: string, planId: string, resources: [...] }',
      });
      return;
    }

    const isStringArray = body.products.every(p => typeof p === 'string');
    const isObjectArray = body.products.every(
      p => typeof p === 'object' && p !== null && 'id' in p && 'namespace' in p,
    );

    if (!isStringArray && !isObjectArray) {
      res.status(400).json({
        error: 'products must be string[] or {id: string, namespace: string}[]',
      });
      return;
    }

    if (isStringArray && (typeof body.namespace !== 'string' || !body.namespace)) {
      res.status(400).json({
        error: 'When products is string[], namespace (string) is required at top level',
      });
      return;
    }

    const clusterDomain =
      process.env.GATEFORGE_CLUSTER_DOMAIN || process.env.CLUSTER_DOMAIN || '';

    let productEntries: { systemName: string; name: string; namespace: string; authType: string }[];
    if (isStringArray) {
      productEntries = (body.products as string[]).map(systemName => ({
        systemName,
        name: systemName,
        namespace: body.namespace!,
        authType: 'api-key',
      }));
    } else {
      productEntries = (body.products as { id: string; namespace: string }[]).map(p => ({
        systemName: p.id,
        name: p.id,
        namespace: p.namespace,
        authType: 'api-key',
      }));
    }

    const event: MigrationEvent = {
      event: 'migration-applied',
      planId: body.planId,
      products: productEntries,
      resources: body.resources,
      clusterDomain,
      timestamp: new Date().toISOString(),
    };

    processor.registerMigration(event);
    res.status(204).send();
  });

  router.get('/health', (_req: Request, res: Response) => {
    res.json({
      status: 'ok',
      plugin: 'gateforge-devhub',
      version: '0.1.9',
      type: 'native-dynamic-plugin',
      uptime: process.uptime(),
    });
  });

  router.put('/entity-update/:name', (req: Request, res: Response) => {
    const { name } = req.params;
    const { description, tags, annotations } = req.body as {
      description?: string;
      tags?: string[];
      annotations?: Record<string, string>;
    };

    if (!description && !tags && !annotations) {
      res.status(400).json({ error: 'At least one of description, tags, or annotations is required' });
      return;
    }

    processor.setEntityOverride(name, { description, tags, annotations });
    logger.info(`Entity override saved for '${name}' — will apply on next catalog refresh`);
    res.json({ status: 'saved', name, message: 'Override will apply on next catalog refresh cycle' });
  });

  router.get('/metrics/:namespace/:httproute', async (req: Request, res: Response) => {
    const { namespace, httproute } = req.params;
    const serviceName = httproute.replace(/-route$/, '');
    const end = Math.floor(Date.now() / 1000);
    const start = end - 3600;
    const step = 60;

    const queries = [
      {
        name: 'request_rate',
        query: `sum(rate(istio_requests_total{destination_service_namespace="${namespace}",destination_service_name=~"${serviceName}.*"}[5m]))`,
      },
      {
        name: 'error_rate',
        query: `sum(rate(istio_requests_total{destination_service_namespace="${namespace}",destination_service_name=~"${serviceName}.*",response_code=~"5.."}[5m]))`,
      },
      {
        name: 'p50_latency',
        query: `histogram_quantile(0.50, sum(rate(istio_request_duration_milliseconds_bucket{destination_service_namespace="${namespace}",destination_service_name=~"${serviceName}.*"}[5m])) by (le))`,
      },
      {
        name: 'p95_latency',
        query: `histogram_quantile(0.95, sum(rate(istio_request_duration_milliseconds_bucket{destination_service_namespace="${namespace}",destination_service_name=~"${serviceName}.*"}[5m])) by (le))`,
      },
      {
        name: 'p99_latency',
        query: `histogram_quantile(0.99, sum(rate(istio_request_duration_milliseconds_bucket{destination_service_namespace="${namespace}",destination_service_name=~"${serviceName}.*"}[5m])) by (le))`,
      },
      {
        name: 'traffic_by_status',
        query: `sum by (response_code) (rate(istio_requests_total{destination_service_namespace="${namespace}",destination_service_name=~"${serviceName}.*"}[5m]))`,
      },
    ];

    const thanosUrl = process.env.THANOS_QUERIER_URL || 'https://thanos-querier.openshift-monitoring.svc:9091';
    const token = process.env.SA_TOKEN || '';

    const results: Record<string, unknown> = {};

    for (const q of queries) {
      try {
        const url = `${thanosUrl}/api/v1/query_range?query=${encodeURIComponent(q.query)}&start=${start}&end=${end}&step=${step}`;
        const fetchResp = await fetch(url, {
          headers: {
            Authorization: `Bearer ${token}`,
          },
        });
        const data = await fetchResp.json();
        results[q.name] = data;
      } catch (err) {
        logger.warn(`Metrics query failed for ${q.name}: ${err}`);
        results[q.name] = { status: 'error', error: String(err) };
      }
    }

    res.json({ namespace, httproute, timeRange: { start, end, step }, metrics: results });
  });

  router.get('/topology/:namespace', async (req: Request, res: Response) => {
    const { namespace } = req.params;
    const k8sApiUrl = process.env.KUBERNETES_SERVICE_HOST
      ? `https://${process.env.KUBERNETES_SERVICE_HOST}:${process.env.KUBERNETES_SERVICE_PORT}`
      : 'https://kubernetes.default.svc';
    const token = process.env.SA_TOKEN || '';

    const resourceTypes = [
      { group: 'gateway.networking.k8s.io', version: 'v1', kind: 'Gateway', plural: 'gateways' },
      { group: 'gateway.networking.k8s.io', version: 'v1', kind: 'HTTPRoute', plural: 'httproutes' },
      { group: 'kuadrant.io', version: 'v1', kind: 'AuthPolicy', plural: 'authpolicies' },
      { group: 'kuadrant.io', version: 'v1', kind: 'RateLimitPolicy', plural: 'ratelimitpolicies' },
      { group: 'extensions.kuadrant.io', version: 'v1alpha1', kind: 'PlanPolicy', plural: 'planpolicies' },
      { group: 'devportal.kuadrant.io', version: 'v1alpha1', kind: 'APIProduct', plural: 'apiproducts' },
      { group: 'devportal.kuadrant.io', version: 'v1alpha1', kind: 'APIKey', plural: 'apikeys' },
      { group: 'extensions.kuadrant.io', version: 'v1alpha1', kind: 'TelemetryPolicy', plural: 'telemetrypolicies' },
    ];

    const nodes: Array<{ id: string; kind: string; name: string; namespace: string; data?: unknown }> = [];
    const edges: Array<{ source: string; target: string; label?: string }> = [];

    for (const rt of resourceTypes) {
      try {
        const url = `${k8sApiUrl}/apis/${rt.group}/${rt.version}/namespaces/${namespace}/${rt.plural}`;
        const fetchResp = await fetch(url, {
          headers: { Authorization: `Bearer ${token}` },
        });
        if (!fetchResp.ok) continue;
        const data = await fetchResp.json() as { items?: Array<{ metadata: { name: string; namespace: string }; spec?: { targetRef?: { name?: string; kind?: string }; apiProductRef?: { name?: string } } }> };
        for (const item of data.items || []) {
          const nodeId = `${rt.kind}/${item.metadata.name}`;
          nodes.push({
            id: nodeId,
            kind: rt.kind,
            name: item.metadata.name,
            namespace: item.metadata.namespace || namespace,
          });

          const targetRef = item.spec?.targetRef;
          if (targetRef?.name) {
            const targetKind = targetRef.kind || 'HTTPRoute';
            edges.push({
              source: `${targetKind}/${targetRef.name}`,
              target: nodeId,
              label: 'targets',
            });
          }

          if (rt.kind === 'HTTPRoute') {
            const parentRefs = (item as unknown as { spec?: { parentRefs?: Array<{ name: string }> } }).spec?.parentRefs;
            if (parentRefs) {
              for (const pr of parentRefs) {
                edges.push({
                  source: `Gateway/${pr.name}`,
                  target: nodeId,
                  label: 'parentRef',
                });
              }
            }
          }

          if (rt.kind === 'APIKey') {
            const apiProductRef = item.spec?.apiProductRef;
            if (apiProductRef?.name) {
              edges.push({
                source: `APIProduct/${apiProductRef.name}`,
                target: nodeId,
                label: 'apiProductRef',
              });
            }
          }
        }
      } catch (err) {
        logger.warn(`Failed to fetch ${rt.kind} in ${namespace}: ${err}`);
      }
    }

    res.json({ namespace, nodes, edges });
  });

  return router;
}

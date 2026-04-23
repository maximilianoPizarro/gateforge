import { Router, Request, Response } from 'express';

interface MigrationProduct {
  systemName: string;
  name: string;
  namespace: string;
  authType: string;
  plans?: string[];
}

interface MigrationResource {
  kind: string;
  name: string;
  namespace: string;
}

interface MigrationEvent {
  event: 'migration-applied' | 'migration-reverted';
  planId: string;
  products: MigrationProduct[];
  resources: MigrationResource[];
  clusterDomain: string;
  timestamp?: string;
}

const migrationEvents: MigrationEvent[] = [];

export function createRouter(): Router {
  const router = Router();

  router.post('/migration-event', (req: Request, res: Response) => {
    const event = req.body as MigrationEvent;

    if (!event.event || !event.planId || !event.products) {
      res.status(400).json({ error: 'Invalid migration event payload' });
      return;
    }

    event.timestamp = event.timestamp ?? new Date().toISOString();

    console.log(
      `[GateForge] Received ${event.event} for plan ${event.planId} ` +
      `with ${event.products.length} products`,
    );

    migrationEvents.push(event);

    if (event.event === 'migration-applied') {
      for (const product of event.products) {
        const entity = buildCatalogEntity(product, event);
        console.log(
          `[GateForge] Catalog entity: ${entity.metadata.name} (ns: ${entity.metadata.namespace})`,
        );
      }
    }

    if (event.event === 'migration-reverted') {
      for (const product of event.products) {
        console.log(`[GateForge] Reverted: ${product.systemName}`);
      }
    }

    res.status(200).json({
      status: 'accepted',
      planId: event.planId,
      entitiesProcessed: event.products.length,
    });
  });

  router.get('/events', (_req: Request, res: Response) => {
    res.json({
      total: migrationEvents.length,
      events: migrationEvents.slice(-100),
    });
  });

  router.get('/health', (_req: Request, res: Response) => {
    res.json({
      status: 'ok',
      plugin: 'gateforge-devhub',
      version: '0.1.7',
      events: migrationEvents.length,
      uptime: process.uptime(),
    });
  });

  return router;
}

function buildCatalogEntity(product: MigrationProduct, event: MigrationEvent) {
  const httpRoute = event.resources.find(r => r.kind === 'HTTPRoute');
  const apiProduct = event.resources.find(r => r.kind === 'APIProduct');
  const hostname = `${product.systemName}.${event.clusterDomain}`;

  return {
    apiVersion: 'backstage.io/v1alpha1',
    kind: 'Component',
    metadata: {
      name: `${product.systemName}-connectivity-link`,
      namespace: 'default',
      description: `${product.name} — migrated from 3scale to Connectivity Link by GateForge`,
      annotations: {
        'kuadrant.io/namespace': product.namespace,
        'kuadrant.io/httproute': httpRoute?.name ?? `${product.systemName}-route`,
        'kuadrant.io/apiproduct': apiProduct?.name ?? `${product.systemName}-api`,
        'backstage.io/kubernetes-namespace': product.namespace,
        'backstage.io/kubernetes-id': product.systemName,
      },
      tags: ['connectivity-link', 'kuadrant', 'gateforge-migrated', product.authType],
      links: [
        { title: 'API Gateway Endpoint', url: `https://${hostname}` },
        { title: 'Swagger UI', url: `https://${hostname}/q/swagger-ui` },
      ],
    },
    spec: {
      type: 'service',
      lifecycle: 'production',
      owner: 'group:default/3scale',
      system: 'gateforge-migrated-apis',
      providesApis: [product.systemName],
    },
  };
}

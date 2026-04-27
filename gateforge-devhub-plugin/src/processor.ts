import { Entity } from '@backstage/catalog-model';
import {
  CatalogProcessor,
  CatalogProcessorCache,
  CatalogProcessorEmit,
} from '@backstage/plugin-catalog-node';
import { LocationSpec } from '@backstage/plugin-catalog-common';
import { LoggerService } from '@backstage/backend-plugin-api';

export interface MigrationProduct {
  systemName: string;
  name: string;
  namespace: string;
  authType: string;
  plans?: string[];
}

export interface MigrationResource {
  kind: string;
  name: string;
  namespace: string;
}

export interface MigrationEvent {
  event: 'migration-applied' | 'migration-reverted';
  planId: string;
  products: MigrationProduct[];
  resources: MigrationResource[];
  clusterDomain: string;
  timestamp?: string;
}

/**
 * Enriches API entities discovered by the 3scale backend plugin with
 * Kuadrant-specific annotations so the Kuadrant frontend plugin can
 * resolve the corresponding APIProduct, APIKey, and plan resources.
 *
 * The processor matches entities whose managed-by-origin-location starts
 * with the 3scale admin URL, then checks the in-memory migration registry
 * for a product whose systemName equals the entity name. When a match is
 * found it injects backstage.io/kubernetes-namespace, kuadrant.io/*
 * annotations, and the kubernetes-id label.
 */
export class GateForgeKuadrantProcessor implements CatalogProcessor {
  private readonly logger: LoggerService;
  private readonly migrations: Map<string, MigrationEvent> = new Map();

  constructor(logger: LoggerService) {
    this.logger = logger;
  }

  getProcessorName(): string {
    return 'gateforge-kuadrant-enrichment';
  }

  registerMigration(event: MigrationEvent): void {
    for (const product of event.products) {
      this.migrations.set(product.systemName, event);
    }
    this.logger.info(
      `Processor: indexed ${event.products.length} products from plan ${event.planId}`,
    );
  }

  removeMigration(event: MigrationEvent): void {
    for (const product of event.products) {
      this.migrations.delete(product.systemName);
    }
  }

  async preProcessEntity(
    entity: Entity,
    _location: LocationSpec,
    _emit: CatalogProcessorEmit,
    _originLocation: LocationSpec,
    _cache: CatalogProcessorCache,
  ): Promise<Entity> {
    if (entity.kind !== 'API') return entity;

    const origin =
      entity.metadata.annotations?.['backstage.io/managed-by-origin-location'] ?? '';
    if (!origin.startsWith('url:https://3scale')) return entity;

    const entityName = entity.metadata.name;
    const migrationEvent = this.migrations.get(entityName);
    if (!migrationEvent) return entity;

    const product = migrationEvent.products.find(p => p.systemName === entityName);
    if (!product) return entity;

    const httpRoute =
      migrationEvent.resources.find(r => r.kind === 'HTTPRoute' && r.name === `${entityName}-route`) ??
      migrationEvent.resources.find(r => r.kind === 'HTTPRoute');
    const apiProduct =
      migrationEvent.resources.find(r => r.kind === 'APIProduct' && r.name === entityName) ??
      migrationEvent.resources.find(r => r.kind === 'APIProduct');

    const enriched: Entity = {
      ...entity,
      metadata: {
        ...entity.metadata,
        annotations: {
          ...entity.metadata.annotations,
          'backstage.io/kubernetes-namespace': product.namespace,
          'backstage.io/kubernetes-id': product.systemName,
          'kuadrant.io/namespace': product.namespace,
          'kuadrant.io/httproute':
            httpRoute?.name ?? `${product.systemName}-route`,
          'kuadrant.io/apiproduct':
            apiProduct?.name ?? product.systemName,
        },
      },
    };

    this.logger.info(
      `Enriched 3scale API entity '${entityName}' with Kuadrant annotations → namespace=${product.namespace}`,
    );

    return enriched;
  }
}

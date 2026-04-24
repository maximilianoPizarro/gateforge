import {
  coreServices,
  createBackendModule,
} from '@backstage/backend-plugin-api';
import { catalogProcessingExtensionPoint } from '@backstage/plugin-catalog-node/alpha';
import { GateForgeKuadrantProcessor } from './processor';
import { createRouter } from './router';

export const catalogModuleGateforge = createBackendModule({
  pluginId: 'catalog',
  moduleId: 'gateforge-entity-provider',
  register(env) {
    env.registerInit({
      deps: {
        catalog: catalogProcessingExtensionPoint,
        logger: coreServices.logger,
        httpRouter: coreServices.httpRouter,
      },
      async init({ catalog, logger, httpRouter }) {
        const processor = new GateForgeKuadrantProcessor(logger);
        catalog.addProcessor(processor);

        const router = createRouter({ logger });
        httpRouter.use(router);

        httpRouter.addAuthPolicy({
          path: '/health',
          allow: 'unauthenticated',
        });
        httpRouter.addAuthPolicy({
          path: '/metrics',
          allow: 'unauthenticated',
        });
        httpRouter.addAuthPolicy({
          path: '/topology',
          allow: 'unauthenticated',
        });

        logger.info(
          'GateForge catalog module registered — processor + metrics/topology endpoints active',
        );
      },
    });
  },
});

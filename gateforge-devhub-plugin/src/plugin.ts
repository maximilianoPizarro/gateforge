import {
  coreServices,
  createBackendPlugin,
} from '@backstage/backend-plugin-api';
import { createRouter } from './router';

export const gateforgePlugin = createBackendPlugin({
  pluginId: 'gateforge',
  register(env) {
    env.registerInit({
      deps: {
        logger: coreServices.logger,
        httpRouter: coreServices.httpRouter,
      },
      async init({ logger, httpRouter }) {
        logger.info('GateForge DevHub plugin initializing...');
        const router = createRouter({ logger });
        httpRouter.use(router);
        logger.info('GateForge DevHub plugin ready — POST /api/gateforge/migration-event');
      },
    });
  },
});

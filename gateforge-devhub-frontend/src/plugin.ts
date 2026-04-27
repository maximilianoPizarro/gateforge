import {
  createPlugin,
  createRouteRef,
  createRoutableExtension,
} from '@backstage/core-plugin-api';

const observabilityRouteRef = createRouteRef({ id: 'gateforge-observability' });
const topologyRouteRef = createRouteRef({ id: 'gateforge-topology' });
const componentEditorRouteRef = createRouteRef({ id: 'gateforge-component-editor' });

export const gateforgePlugin = createPlugin({
  id: 'gateforge',
  routes: {
    observability: observabilityRouteRef,
    topology: topologyRouteRef,
    componentEditor: componentEditorRouteRef,
  },
});

export const GateForgeObservabilityTab = gateforgePlugin.provide(
  createRoutableExtension({
    name: 'GateForgeObservabilityTab',
    component: () =>
      import('./components/ObservabilityTab/ObservabilityTab').then(
        m => m.ObservabilityTab,
      ),
    mountPoint: observabilityRouteRef,
  }),
);

export const GateForgeTopologyTab = gateforgePlugin.provide(
  createRoutableExtension({
    name: 'GateForgeTopologyTab',
    component: () =>
      import('./components/TopologyTab/TopologyTab').then(
        m => m.TopologyTab,
      ),
    mountPoint: topologyRouteRef,
  }),
);

export const GateForgeComponentEditorTab = gateforgePlugin.provide(
  createRoutableExtension({
    name: 'GateForgeComponentEditorTab',
    component: () =>
      import('./components/ComponentEditorTab/ComponentEditorTab').then(
        m => m.ComponentEditorTab,
      ),
    mountPoint: componentEditorRouteRef,
  }),
);

import {
  createPlugin,
  createRoutableExtension,
} from '@backstage/core-plugin-api';

export const gateforgePlugin = createPlugin({
  id: 'gateforge',
});

export const GateForgeObservabilityTab = gateforgePlugin.provide(
  createRoutableExtension({
    name: 'GateForgeObservabilityTab',
    component: () =>
      import('./components/ObservabilityTab/ObservabilityTab').then(
        m => m.ObservabilityTab,
      ),
    mountPoint: { id: 'gateforge-observability' } as any,
  }),
);

export const GateForgeTopologyTab = gateforgePlugin.provide(
  createRoutableExtension({
    name: 'GateForgeTopologyTab',
    component: () =>
      import('./components/TopologyTab/TopologyTab').then(
        m => m.TopologyTab,
      ),
    mountPoint: { id: 'gateforge-topology' } as any,
  }),
);

export const GateForgeComponentEditorTab = gateforgePlugin.provide(
  createRoutableExtension({
    name: 'GateForgeComponentEditorTab',
    component: () =>
      import('./components/ComponentEditorTab/ComponentEditorTab').then(
        m => m.ComponentEditorTab,
      ),
    mountPoint: { id: 'gateforge-component-editor' } as any,
  }),
);

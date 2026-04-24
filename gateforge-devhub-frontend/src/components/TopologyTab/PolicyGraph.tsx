import React, { useMemo } from 'react';
import { makeStyles } from '@material-ui/core';

interface Node {
  id: string;
  kind: string;
  name: string;
  namespace: string;
}

interface Edge {
  source: string;
  target: string;
  label?: string;
}

interface PolicyGraphProps {
  nodes: Node[];
  edges: Edge[];
}

const kindColors: Record<string, string> = {
  Gateway: '#0066cc',
  HTTPRoute: '#3f9c35',
  AuthPolicy: '#ee0000',
  RateLimitPolicy: '#f0ab00',
  PlanPolicy: '#6a6e73',
  APIProduct: '#8a5500',
  APIKey: '#004080',
  TelemetryPolicy: '#6c3483',
};

const kindOrder = [
  'Gateway', 'HTTPRoute', 'AuthPolicy', 'RateLimitPolicy',
  'PlanPolicy', 'TelemetryPolicy', 'APIProduct', 'APIKey',
];

const useStyles = makeStyles(() => ({
  container: {
    width: '100%',
    overflowX: 'auto',
  },
  svg: {
    display: 'block',
    margin: '0 auto',
  },
}));

export const PolicyGraph: React.FC<PolicyGraphProps> = ({ nodes, edges }) => {
  const classes = useStyles();

  const layout = useMemo(() => {
    const groups: Record<string, Node[]> = {};
    for (const n of nodes) {
      if (!groups[n.kind]) groups[n.kind] = [];
      groups[n.kind].push(n);
    }

    const sortedKinds = kindOrder.filter(k => groups[k]);
    const unlistedKinds = Object.keys(groups).filter(k => !kindOrder.includes(k));
    const allKinds = [...sortedKinds, ...unlistedKinds];

    const nodeW = 180;
    const nodeH = 44;
    const colGap = 60;
    const rowGap = 24;

    const positions: Record<string, { x: number; y: number }> = {};
    let x = 40;
    let maxY = 0;

    for (const kind of allKinds) {
      const items = groups[kind];
      let y = 40;
      for (const item of items) {
        positions[item.id] = { x, y };
        y += nodeH + rowGap;
      }
      if (y > maxY) maxY = y;
      x += nodeW + colGap;
    }

    const svgW = x + 40;
    const svgH = maxY + 40;

    return { positions, svgW, svgH, nodeW, nodeH };
  }, [nodes]);

  const { positions, svgW, svgH, nodeW, nodeH } = layout;

  return (
    <div className={classes.container}>
      <svg width={svgW} height={svgH} className={classes.svg}>
        <defs>
          <marker id="arrowhead" markerWidth="8" markerHeight="6" refX="8" refY="3" orient="auto">
            <polygon points="0 0, 8 3, 0 6" fill="#6a6e73" />
          </marker>
        </defs>

        {edges.map((edge, i) => {
          const src = positions[edge.source];
          const tgt = positions[edge.target];
          if (!src || !tgt) return null;

          const x1 = src.x + nodeW;
          const y1 = src.y + nodeH / 2;
          const x2 = tgt.x;
          const y2 = tgt.y + nodeH / 2;

          return (
            <line
              key={`edge-${i}`}
              x1={x1} y1={y1}
              x2={x2} y2={y2}
              stroke="#d2d2d2"
              strokeWidth={1.5}
              markerEnd="url(#arrowhead)"
            />
          );
        })}

        {nodes.map(node => {
          const pos = positions[node.id];
          if (!pos) return null;
          const color = kindColors[node.kind] || '#6a6e73';

          return (
            <g key={node.id}>
              <rect
                x={pos.x} y={pos.y}
                width={nodeW} height={nodeH}
                rx={6} ry={6}
                fill="white"
                stroke={color}
                strokeWidth={2}
              />
              <rect
                x={pos.x} y={pos.y}
                width={6} height={nodeH}
                rx={3} ry={0}
                fill={color}
              />
              <text
                x={pos.x + 14} y={pos.y + 16}
                fontSize={10} fontWeight={600} fill={color}
              >
                {node.kind}
              </text>
              <text
                x={pos.x + 14} y={pos.y + 32}
                fontSize={11} fill="#151515"
              >
                {node.name.length > 18 ? `${node.name.substring(0, 18)}…` : node.name}
              </text>
            </g>
          );
        })}
      </svg>
    </div>
  );
};

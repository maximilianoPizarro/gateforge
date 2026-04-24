import React, { useEffect, useState } from 'react';
import {
  Card,
  CardContent,
  CardHeader,
  Typography,
  CircularProgress,
  Box,
  Chip,
  makeStyles,
} from '@material-ui/core';
import { useEntity } from '@backstage/plugin-catalog-react';
import { PolicyGraph } from './PolicyGraph';

interface TopologyNode {
  id: string;
  kind: string;
  name: string;
  namespace: string;
}

interface TopologyEdge {
  source: string;
  target: string;
  label?: string;
}

interface TopologyData {
  namespace: string;
  nodes: TopologyNode[];
  edges: TopologyEdge[];
}

const useStyles = makeStyles(theme => ({
  summary: {
    display: 'flex',
    gap: theme.spacing(1),
    flexWrap: 'wrap',
    marginBottom: theme.spacing(2),
  },
}));

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

export const TopologyTab = () => {
  const classes = useStyles();
  const { entity } = useEntity();
  const [data, setData] = useState<TopologyData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const namespace = entity.metadata.annotations?.['kuadrant.io/namespace'] || '';

  useEffect(() => {
    if (!namespace) {
      setError('Missing kuadrant.io/namespace annotation');
      setLoading(false);
      return;
    }

    fetch(`/api/catalog/gateforge-entity-provider/topology/${namespace}`)
      .then(r => r.json())
      .then(d => {
        setData(d as TopologyData);
        setLoading(false);
      })
      .catch(err => {
        setError(`Failed to load topology: ${err.message}`);
        setLoading(false);
      });
  }, [namespace]);

  if (loading) {
    return (
      <Box display="flex" justifyContent="center" alignItems="center" minHeight={300}>
        <CircularProgress />
        <Typography variant="body1" style={{ marginLeft: 16 }}>Loading policy topology...</Typography>
      </Box>
    );
  }

  if (error) {
    return (
      <Card>
        <CardContent>
          <Typography color="error">{error}</Typography>
        </CardContent>
      </Card>
    );
  }

  if (!data || data.nodes.length === 0) {
    return (
      <Card>
        <CardContent>
          <Typography color="textSecondary">No Kuadrant resources found in namespace {namespace}</Typography>
        </CardContent>
      </Card>
    );
  }

  const kindCounts = data.nodes.reduce<Record<string, number>>((acc, n) => {
    acc[n.kind] = (acc[n.kind] || 0) + 1;
    return acc;
  }, {});

  return (
    <Card>
      <CardHeader
        title="Policy Topology — GateForge"
        subheader={`Kuadrant resources in namespace ${namespace}`}
      />
      <CardContent>
        <div className={classes.summary}>
          {Object.entries(kindCounts).map(([kind, count]) => (
            <Chip
              key={kind}
              label={`${kind}: ${count}`}
              size="small"
              style={{ backgroundColor: kindColors[kind] || '#6a6e73', color: '#fff' }}
            />
          ))}
        </div>
        <PolicyGraph nodes={data.nodes} edges={data.edges} />
      </CardContent>
    </Card>
  );
};

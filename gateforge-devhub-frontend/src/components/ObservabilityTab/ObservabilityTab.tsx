import React, { useEffect, useState } from 'react';
import {
  Card,
  CardContent,
  CardHeader,
  Grid,
  Typography,
  CircularProgress,
  Box,
} from '@material-ui/core';
import { useEntity } from '@backstage/plugin-catalog-react';
import { fetchApiRef, useApi } from '@backstage/core-plugin-api';
import { MetricsChart } from './MetricsChart';

interface MetricsData {
  namespace: string;
  httproute: string;
  timeRange: { start: number; end: number; step: number };
  metrics: Record<string, { status: string; data?: { result: Array<{ values: Array<[number, string]> }> } }>;
}

export const ObservabilityTab = () => {
  const { entity } = useEntity();
  const fetchApi = useApi(fetchApiRef);
  const [data, setData] = useState<MetricsData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const namespace = entity.metadata.annotations?.['kuadrant.io/namespace'] || '';
  const httproute = entity.metadata.annotations?.['kuadrant.io/httproute'] || '';

  useEffect(() => {
    if (!namespace || !httproute) {
      setError('Missing kuadrant.io/namespace or kuadrant.io/httproute annotations');
      setLoading(false);
      return;
    }

    const backendUrl = `/api/catalog/metrics/${namespace}/${httproute}`;

    fetchApi.fetch(backendUrl)
      .then(r => r.json())
      .then(d => {
        setData(d as MetricsData);
        setLoading(false);
      })
      .catch(err => {
        setError(`Failed to load metrics: ${err.message}`);
        setLoading(false);
      });
  }, [namespace, httproute]);

  if (loading) {
    return (
      <Box display="flex" justifyContent="center" alignItems="center" minHeight={300}>
        <CircularProgress />
        <Typography variant="body1" style={{ marginLeft: 16 }}>Loading API metrics...</Typography>
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

  if (!data || !data.metrics) {
    return (
      <Card>
        <CardContent>
          <Typography color="textSecondary">
            No metrics available. The Thanos querier may not be configured or reachable for this namespace.
          </Typography>
        </CardContent>
      </Card>
    );
  }

  const extractTimeSeries = (metricKey: string): Array<{ time: string; value: number }> => {
    const metric = data.metrics[metricKey];
    if (!metric?.data?.result?.[0]?.values) return [];
    return metric.data.result[0].values.map(([ts, val]) => ({
      time: new Date(ts * 1000).toLocaleTimeString(),
      value: parseFloat(val) || 0,
    }));
  };

  return (
    <Grid container spacing={3}>
      <Grid item xs={12}>
        <Card>
          <CardHeader
            title="API Observability — GateForge"
            subheader={`Metrics for ${httproute} in namespace ${namespace} (last 1h)`}
          />
        </Card>
      </Grid>

      <Grid item xs={12} md={6}>
        <Card>
          <CardHeader title="Request Rate" subheader="Requests per second (5m avg)" />
          <CardContent>
            <MetricsChart data={extractTimeSeries('request_rate')} color="#0066cc" label="req/s" />
          </CardContent>
        </Card>
      </Grid>

      <Grid item xs={12} md={6}>
        <Card>
          <CardHeader title="Error Rate" subheader="5xx errors per second (5m avg)" />
          <CardContent>
            <MetricsChart data={extractTimeSeries('error_rate')} color="#c9190b" label="err/s" />
          </CardContent>
        </Card>
      </Grid>

      <Grid item xs={12} md={4}>
        <Card>
          <CardHeader title="P50 Latency" subheader="Median response time (ms)" />
          <CardContent>
            <MetricsChart data={extractTimeSeries('p50_latency')} color="#3f9c35" label="ms" />
          </CardContent>
        </Card>
      </Grid>

      <Grid item xs={12} md={4}>
        <Card>
          <CardHeader title="P95 Latency" subheader="95th percentile (ms)" />
          <CardContent>
            <MetricsChart data={extractTimeSeries('p95_latency')} color="#f0ab00" label="ms" />
          </CardContent>
        </Card>
      </Grid>

      <Grid item xs={12} md={4}>
        <Card>
          <CardHeader title="P99 Latency" subheader="99th percentile (ms)" />
          <CardContent>
            <MetricsChart data={extractTimeSeries('p99_latency')} color="#ee0000" label="ms" />
          </CardContent>
        </Card>
      </Grid>
    </Grid>
  );
};

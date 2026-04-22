import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface ProjectInfo {
  name: string;
  status: string;
  creationTimestamp: string;
  hasThreeScale: boolean;
  hasKuadrant: boolean;
}

export interface ThreeScaleProduct {
  name: string;
  namespace: string;
  systemName: string;
  description: string;
  deploymentOption: string;
  mappingRules: { httpMethod: string; pattern: string; metricRef: string; delta: number }[];
  backendUsages: { backendName: string; path: string }[];
  authentication: Record<string, unknown>;
  source: string;
  backendNamespace?: string;
  backendServiceName?: string;
}

export interface ThreeScaleBackend {
  name: string;
  namespace?: string;
  id?: number;
  systemName?: string;
  privateEndpoint?: string;
  description?: string;
  source: string;
  createdAt?: string;
  updatedAt?: string;
  spec?: Record<string, unknown>;
}

export interface ThreeScaleStatus {
  configured: boolean;
  crdDiscoveryEnabled: boolean;
  reachable?: boolean;
  productCount?: number;
  backendApiCount?: number;
  activeDocsCount?: number;
  error?: string;
}

export interface GeneratedResource {
  kind: string;
  name: string;
  namespace: string;
  yaml: string;
}

export interface MigrationPlan {
  id: string;
  gatewayStrategy: string;
  sourceProducts: string[];
  resources: GeneratedResource[];
  aiAnalysis: string;
  createdAt: string;
  catalogInfoYaml?: string;
  status?: string;
}

export interface BulkRevertResult {
  totalPlans: number;
  totalReverted: number;
  totalFailed: number;
  planResults: ApplyResult[];
}

export interface TestCommand {
  label: string;
  command: string;
  type: string;
}

export interface AuditEntry {
  id: string;
  timestamp: string;
  action: string;
  resourceKind: string;
  resourceName: string;
  namespace: string;
  yamlBefore: string;
  yamlAfter: string;
  performedBy: string;
}

export interface ChatMessage {
  role: string;
  content: string;
}

export interface ApplyResult {
  planId: string;
  applied: number;
  failed: number;
  results: ResourceResult[];
}

export interface ResourceResult {
  kind: string;
  name: string;
  namespace: string;
  success: boolean;
  message: string;
}

export interface FeatureFlags {
  developerHub: {
    enabled: boolean;
    url: string;
  };
}

@Injectable({ providedIn: 'root' })
export class ApiService {
  private baseUrl = '/api';

  constructor(private http: HttpClient) {}

  getProjects(): Observable<ProjectInfo[]> {
    return this.http.get<ProjectInfo[]>(`${this.baseUrl}/cluster/projects`);
  }

  getProducts(): Observable<ThreeScaleProduct[]> {
    return this.http.get<ThreeScaleProduct[]>(`${this.baseUrl}/threescale/products`);
  }

  getBackends(): Observable<ThreeScaleBackend[]> {
    return this.http.get<ThreeScaleBackend[]>(`${this.baseUrl}/threescale/backends`);
  }

  getThreeScaleStatus(): Observable<ThreeScaleStatus> {
    return this.http.get<ThreeScaleStatus>(`${this.baseUrl}/threescale/status`);
  }

  analyzeMigration(gatewayStrategy: string, products: string[]): Observable<MigrationPlan> {
    return this.http.post<MigrationPlan>(`${this.baseUrl}/migration/analyze`, {
      gatewayStrategy, products
    });
  }

  getPlans(): Observable<MigrationPlan[]> {
    return this.http.get<MigrationPlan[]>(`${this.baseUrl}/migration/plans`);
  }

  getAuditLog(): Observable<AuditEntry[]> {
    return this.http.get<AuditEntry[]>(`${this.baseUrl}/audit/reports`);
  }

  applyPlan(planId: string): Observable<ApplyResult> {
    return this.http.post<ApplyResult>(`${this.baseUrl}/migration/plans/${planId}/apply`, {});
  }

  revertPlan(planId: string): Observable<ApplyResult> {
    return this.http.post<ApplyResult>(`${this.baseUrl}/migration/plans/${planId}/revert`, {});
  }

  revertBulk(planIds: string[], deleteGateway: boolean): Observable<BulkRevertResult> {
    return this.http.post<BulkRevertResult>(`${this.baseUrl}/migration/revert-bulk`, { planIds, deleteGateway });
  }

  getTestCommands(planId: string): Observable<TestCommand[]> {
    return this.http.get<TestCommand[]>(`${this.baseUrl}/migration/plans/${planId}/test-commands`);
  }

  chat(message: string): Observable<ChatMessage> {
    return this.http.post<ChatMessage>(`${this.baseUrl}/chat`, {
      role: 'user', content: message
    });
  }

  getFeatures(): Observable<FeatureFlags> {
    return this.http.get<FeatureFlags>(`${this.baseUrl}/cluster/features`);
  }
}

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

  getBackends(): Observable<unknown[]> {
    return this.http.get<unknown[]>(`${this.baseUrl}/threescale/backends`);
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

  chat(message: string): Observable<ChatMessage> {
    return this.http.post<ChatMessage>(`${this.baseUrl}/chat`, {
      role: 'user', content: message
    });
  }
}

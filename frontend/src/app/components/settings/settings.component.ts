import { Component, CUSTOM_ELEMENTS_SCHEMA, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService, ThreeScaleSource, TargetCluster } from '../../services/api.service';

@Component({
  selector: 'app-settings',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  schemas: [CUSTOM_ELEMENTS_SCHEMA],
  template: `
    <header class="page-header">
      <div class="container">
        <div class="header-row">
          <div>
            <h1>Settings</h1>
            <p class="subtitle">Manage 3scale sources, target clusters, and hub configuration.</p>
          </div>
          <a routerLink="/" class="header-link">Dashboard</a>
        </div>
      </div>
    </header>

    <section class="container main-content">

      <!-- 3SCALE SOURCES -->
      <div class="settings-section">
        <div class="section-head">
          <h2>3scale Sources</h2>
          <span class="count-pill">{{ sources.length }} connected</span>
        </div>
        <p class="section-desc">Connect to multiple 3scale Admin API instances. Products from all sources are merged and deduplicated.</p>

        <div class="sources-list" *ngIf="sources.length > 0">
          <div *ngFor="let s of sources" class="source-row card">
            <div class="source-info">
              <div class="source-header">
                <span class="source-label">{{ s.label || s.id }}</span>
                <span class="badge" [class.badge-ok]="sourceStatuses[s.id]?.['reachable']" [class.badge-off]="!sourceStatuses[s.id]?.['reachable']">
                  {{ sourceStatuses[s.id]?.['reachable'] ? 'Connected' : (sourceStatuses[s.id] ? 'Unreachable' : 'Checking...') }}
                </span>
                <span class="badge badge-id">{{ s.id }}</span>
              </div>
              <div class="source-meta">
                <code>{{ s.adminUrl }}</code>
                <span class="pill" *ngIf="s.enabled">Enabled</span>
                <span class="pill pill-off" *ngIf="!s.enabled">Disabled</span>
              </div>
            </div>
            <div class="source-actions">
              <button type="button" class="btn-check" (click)="checkSourceStatus(s.id)">Check</button>
              <button type="button" class="btn-remove" (click)="removeSource(s.id)" *ngIf="s.id !== 'default'">Remove</button>
            </div>
          </div>
        </div>

        <div *ngIf="sources.length === 0 && !sourcesLoading" class="empty-card card">
          <p>No 3scale sources configured. Add one below or configure via Helm values.</p>
        </div>

        <div class="add-form card" *ngIf="showAddSource">
          <h3>Add 3scale Source</h3>
          <div class="form-grid">
            <div class="form-group">
              <label>Source ID</label>
              <input type="text" [(ngModel)]="newSource.id" placeholder="e.g. production">
            </div>
            <div class="form-group">
              <label>Label</label>
              <input type="text" [(ngModel)]="newSource.label" placeholder="e.g. Production 3scale">
            </div>
            <div class="form-group full">
              <label>Admin API URL</label>
              <input type="text" [(ngModel)]="newSource.adminUrl" placeholder="https://3scale-admin.example.com">
            </div>
            <div class="form-group full">
              <label>Access Token</label>
              <input type="password" [(ngModel)]="newSource.accessToken" placeholder="Access token">
            </div>
          </div>
          <div class="form-actions">
            <button type="button" class="btn-save" (click)="addSource()" [disabled]="!newSource.id || !newSource.adminUrl || !newSource.accessToken">Add Source</button>
            <button type="button" class="btn-cancel" (click)="showAddSource = false">Cancel</button>
          </div>
        </div>
        <button type="button" class="btn-add" (click)="showAddSource = true" *ngIf="!showAddSource">+ Add 3scale Source</button>
      </div>

      <!-- TARGET CLUSTERS -->
      <div class="settings-section">
        <div class="section-head">
          <h2>Target Clusters</h2>
          <span class="count-pill">{{ clusters.length }} clusters</span>
        </div>
        <p class="section-desc">Manage OpenShift clusters where migrations can be deployed. The local cluster is always available.</p>

        <div class="sources-list" *ngIf="clusters.length > 0">
          <div *ngFor="let c of clusters" class="source-row card">
            <div class="source-info">
              <div class="source-header">
                <span class="source-label">{{ c.label }}</span>
                <span class="badge badge-id">{{ c.id }}</span>
                <span class="badge" [class.badge-ok]="clusterStatuses[c.id]?.['valid']" [class.badge-off]="clusterStatuses[c.id] && !clusterStatuses[c.id]?.['valid']">
                  {{ clusterStatuses[c.id]?.['valid'] ? 'Valid' : (clusterStatuses[c.id] ? 'Invalid' : '—') }}
                </span>
              </div>
              <div class="source-meta">
                <code *ngIf="c.apiServerUrl">{{ c.apiServerUrl }}</code>
                <span class="pill" *ngIf="c.id === 'local'">In-cluster</span>
                <span class="pill" *ngIf="c.authType && c.id !== 'local'">{{ c.authType }}</span>
              </div>
            </div>
            <div class="source-actions">
              <button type="button" class="btn-check" (click)="validateCluster(c.id)" *ngIf="c.id !== 'local'">Validate</button>
              <button type="button" class="btn-remove" (click)="removeCluster(c.id)" *ngIf="c.id !== 'local'">Remove</button>
            </div>
          </div>
        </div>

        <div class="add-form card" *ngIf="showAddCluster">
          <h3>Add Target Cluster</h3>
          <div class="form-grid">
            <div class="form-group">
              <label>Cluster ID</label>
              <input type="text" [(ngModel)]="newCluster.id" placeholder="e.g. staging">
            </div>
            <div class="form-group">
              <label>Label</label>
              <input type="text" [(ngModel)]="newCluster.label" placeholder="e.g. Staging Cluster">
            </div>
            <div class="form-group full">
              <label>API Server URL</label>
              <input type="text" [(ngModel)]="newCluster.apiServerUrl" placeholder="https://api.cluster.example.com:6443">
            </div>
            <div class="form-group full">
              <label>Token</label>
              <input type="password" [(ngModel)]="newCluster.token" placeholder="Bearer token or kubeconfig token">
            </div>
          </div>
          <div class="form-actions">
            <button type="button" class="btn-save" (click)="addCluster()" [disabled]="!newCluster.id || !newCluster.apiServerUrl || !newCluster.token">Add Cluster</button>
            <button type="button" class="btn-cancel" (click)="showAddCluster = false">Cancel</button>
          </div>
        </div>
        <button type="button" class="btn-add" (click)="showAddCluster = true" *ngIf="!showAddCluster">+ Add Target Cluster</button>
      </div>

    </section>
  `,
  styles: [`
    .page-header { background: #151515; color: white; padding: 32px 0; }
    .page-header h1 { font-family: 'Red Hat Display', sans-serif; font-size: 1.75rem; font-weight: 700; margin: 0 0 8px; }
    .subtitle { margin: 0; color: #c9c9c9; max-width: 720px; line-height: 1.5; }
    .container { max-width: 1280px; margin: 0 auto; padding: 0 24px; }
    .header-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
    .header-link { color: white; text-decoration: none; font-weight: 600; padding: 8px 16px; border: 1px solid rgba(255,255,255,0.35); border-radius: 6px; align-self: center; }
    .header-link:hover { background: rgba(255,255,255,0.08); text-decoration: none; }
    .main-content { padding: 28px 0 56px; }

    .settings-section { margin-bottom: 40px; }
    .section-head { display: flex; align-items: center; gap: 12px; margin-bottom: 4px; }
    .section-head h2 { font-family: 'Red Hat Display', sans-serif; font-size: 1.35rem; margin: 0; color: #151515; }
    .section-desc { color: #6a6e73; margin: 0 0 20px; max-width: 720px; line-height: 1.5; }
    .count-pill { background: #e8e8e8; color: #6a6e73; font-size: 0.78rem; padding: 3px 12px; border-radius: 999px; font-weight: 500; }

    .card { background: white; border: 1px solid #d2d2d2; border-radius: 8px; }
    .empty-card { padding: 24px; color: #6a6e73; text-align: center; }

    .sources-list { display: flex; flex-direction: column; gap: 10px; margin-bottom: 16px; }
    .source-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 16px 20px; flex-wrap: wrap; }
    .source-info { flex: 1; min-width: 0; }
    .source-header { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 6px; }
    .source-label { font-weight: 600; color: #151515; font-size: 0.95rem; }
    .source-meta { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    .source-meta code { background: #f5f5f5; padding: 2px 8px; border-radius: 4px; font-size: 0.82rem; color: #6a6e73; word-break: break-all; }
    .source-actions { display: flex; gap: 8px; flex-shrink: 0; }

    .badge { display: inline-block; padding: 2px 10px; border-radius: 999px; font-size: 0.72rem; font-weight: 600; }
    .badge-ok { background: #e6f5e0; color: #2d6b24; }
    .badge-off { background: #fef3cd; color: #8a5500; }
    .badge-id { background: #f5f5f5; color: #6a6e73; }
    .pill { font-size: 0.75rem; padding: 2px 10px; border-radius: 999px; background: #e6f5e0; color: #2d6b24; }
    .pill-off { background: #f5f5f5; color: #6a6e73; }

    .btn-check { padding: 6px 14px; border: 1px solid #0066cc; border-radius: 4px; background: white; color: #0066cc; cursor: pointer; font-size: 0.82rem; font-weight: 600; font-family: inherit; }
    .btn-check:hover { background: #e6f0ff; }
    .btn-remove { padding: 6px 14px; border: 1px solid #c9190b; border-radius: 4px; background: white; color: #c9190b; cursor: pointer; font-size: 0.82rem; font-weight: 600; font-family: inherit; }
    .btn-remove:hover { background: #fef3f3; }
    .btn-add { padding: 10px 20px; border: 2px dashed #d2d2d2; border-radius: 8px; background: white; cursor: pointer; font-size: 0.9rem; font-weight: 600; color: #0066cc; width: 100%; font-family: inherit; }
    .btn-add:hover { border-color: #0066cc; background: #f0f7ff; }

    .add-form { padding: 20px 24px; margin-bottom: 16px; }
    .add-form h3 { font-family: 'Red Hat Display', sans-serif; font-size: 1.05rem; margin: 0 0 16px; color: #151515; }
    .form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; margin-bottom: 16px; }
    .form-group { display: flex; flex-direction: column; gap: 4px; }
    .form-group.full { grid-column: 1 / -1; }
    .form-group label { font-size: 0.82rem; font-weight: 600; color: #151515; }
    .form-group input { padding: 8px 12px; border: 1px solid #d2d2d2; border-radius: 6px; font-size: 0.9rem; font-family: inherit; }
    .form-group input:focus { outline: none; border-color: #0066cc; box-shadow: 0 0 0 2px rgba(0,102,204,0.15); }
    .form-actions { display: flex; gap: 10px; }
    .btn-save { padding: 8px 20px; border: none; border-radius: 6px; background: #3f9c35; color: white; cursor: pointer; font-weight: 600; font-size: 0.9rem; font-family: inherit; }
    .btn-save:hover:not(:disabled) { background: #2d6b24; }
    .btn-save:disabled { opacity: 0.5; cursor: not-allowed; }
    .btn-cancel { padding: 8px 20px; border: 1px solid #d2d2d2; border-radius: 6px; background: white; cursor: pointer; font-weight: 600; font-size: 0.9rem; font-family: inherit; }
    .btn-cancel:hover { border-color: #151515; }

    @media (max-width: 640px) {
      .form-grid { grid-template-columns: 1fr; }
      .form-group.full { grid-column: auto; }
    }
  `]
})
export class SettingsComponent implements OnInit {
  sources: ThreeScaleSource[] = [];
  clusters: TargetCluster[] = [];
  sourcesLoading = true;
  sourceStatuses: Record<string, Record<string, unknown>> = {};
  clusterStatuses: Record<string, Record<string, unknown>> = {};
  showAddSource = false;
  showAddCluster = false;

  newSource: Partial<ThreeScaleSource> = { id: '', label: '', adminUrl: '', accessToken: '', enabled: true };
  newCluster: Partial<TargetCluster> = { id: '', label: '', apiServerUrl: '', token: '', authType: 'token', verifySsl: false, enabled: true };

  constructor(private api: ApiService) {}

  ngOnInit(): void {
    this.loadSources();
    this.loadClusters();
  }

  loadSources(): void {
    this.sourcesLoading = true;
    this.api.getSources().subscribe({
      next: (s) => {
        this.sources = s;
        this.sourcesLoading = false;
        s.forEach(src => this.checkSourceStatus(src.id));
      },
      error: () => this.sourcesLoading = false
    });
  }

  loadClusters(): void {
    this.api.getTargetClusters().subscribe({
      next: (c) => this.clusters = c,
      error: () => this.clusters = [{ id: 'local', label: 'Local (in-cluster)', apiServerUrl: '', token: '', authType: 'in-cluster', verifySsl: true, enabled: true }]
    });
  }

  checkSourceStatus(id: string): void {
    this.api.getSourceStatus(id).subscribe({
      next: (status) => this.sourceStatuses = { ...this.sourceStatuses, [id]: status },
      error: () => this.sourceStatuses = { ...this.sourceStatuses, [id]: { reachable: false, error: 'Failed to check' } }
    });
  }

  validateCluster(id: string): void {
    this.api.validateTargetCluster(id).subscribe({
      next: (result) => this.clusterStatuses = { ...this.clusterStatuses, [id]: { valid: true, ...result } },
      error: () => this.clusterStatuses = { ...this.clusterStatuses, [id]: { valid: false } }
    });
  }

  addSource(): void {
    const source: ThreeScaleSource = {
      id: this.newSource.id!,
      label: this.newSource.label || this.newSource.id!,
      adminUrl: this.newSource.adminUrl!,
      accessToken: this.newSource.accessToken!,
      enabled: true
    };
    this.api.addSource(source).subscribe({
      next: () => {
        this.showAddSource = false;
        this.newSource = { id: '', label: '', adminUrl: '', accessToken: '', enabled: true };
        this.loadSources();
      }
    });
  }

  removeSource(id: string): void {
    if (!confirm(`Remove 3scale source "${id}"? This will stop discovering products from this source.`)) return;
    this.api.removeSource(id).subscribe({ next: () => this.loadSources() });
  }

  addCluster(): void {
    const cluster: TargetCluster = {
      id: this.newCluster.id!,
      label: this.newCluster.label || this.newCluster.id!,
      apiServerUrl: this.newCluster.apiServerUrl!,
      token: this.newCluster.token!,
      authType: 'token',
      verifySsl: false,
      enabled: true
    };
    this.api.addTargetCluster(cluster).subscribe({
      next: () => {
        this.showAddCluster = false;
        this.newCluster = { id: '', label: '', apiServerUrl: '', token: '', authType: 'token', verifySsl: false, enabled: true };
        this.loadClusters();
      }
    });
  }

  removeCluster(id: string): void {
    if (!confirm(`Remove target cluster "${id}"?`)) return;
    this.api.removeTargetCluster(id).subscribe({ next: () => this.loadClusters() });
  }
}

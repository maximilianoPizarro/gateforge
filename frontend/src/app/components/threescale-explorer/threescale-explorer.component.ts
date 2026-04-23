import { Component, CUSTOM_ELEMENTS_SCHEMA, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { ApiService, ThreeScaleProduct, ThreeScaleBackend, ThreeScaleStatus } from '../../services/api.service';

type SourceTab = 'all' | 'crd' | 'admin-api';

@Component({
  selector: 'app-threescale-explorer',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  schemas: [CUSTOM_ELEMENTS_SCHEMA],
  template: `
    <header class="page-header">
      <div class="container">
        <div class="header-row">
          <div>
            <h1>3scale Explorer</h1>
            <p class="subtitle">Discover Products, Backends and Mapping Rules from CRDs and the 3scale Admin API.</p>
          </div>
          <a routerLink="/" class="header-link">Dashboard</a>
        </div>

        <div class="status-bar" *ngIf="!loading">
          <div class="status-pill" [class.ok]="status?.crdDiscoveryEnabled" [class.off]="!status?.crdDiscoveryEnabled">
            <span class="status-dot"></span>
            CRD Discovery
          </div>
          <div class="status-pill" [class.ok]="status?.configured && status?.reachable" [class.off]="!status?.configured || !status?.reachable">
            <span class="status-dot"></span>
            Admin API {{ status?.configured ? (status?.reachable ? 'Connected' : 'Unreachable') : 'Not configured' }}
          </div>
        </div>

        <div class="stats-bar" *ngIf="!loading">
          <div class="stat-pill">
            <span class="stat-value">{{ products.length }}</span>
            <span class="stat-label">Products</span>
          </div>
          <div class="stat-pill">
            <span class="stat-value">{{ backends.length }}</span>
            <span class="stat-label">Backends</span>
          </div>
          <div class="stat-pill" *ngIf="crdCount > 0">
            <span class="stat-value">{{ crdCount }}</span>
            <span class="stat-label">from CRDs</span>
          </div>
          <div class="stat-pill api-pill" *ngIf="apiCount > 0">
            <span class="stat-value">{{ apiCount }}</span>
            <span class="stat-label">from Admin API</span>
          </div>
          <div class="stat-pill accent" *ngIf="totalMappingRules > 0">
            <span class="stat-value">{{ totalMappingRules }}</span>
            <span class="stat-label">Mapping Rules</span>
          </div>
          <div class="stat-pill" *ngIf="totalMappingRules === 0">
            <span class="stat-value">Auto</span>
            <span class="stat-label">Path Discovery</span>
          </div>
        </div>
      </div>
    </header>

    <section class="container main-content">

      <!-- LOADING -->
      <div *ngIf="loading" class="loading-state">
        <div class="loader-center">
          <div class="pulse-ring"></div>
          <p class="loading-stage">{{ loadingStage }}</p>
        </div>
        <div class="skeleton-grid-cards">
          <div *ngFor="let i of [1,2,3,4,5,6]" class="skeleton-card">
            <div class="skeleton-line long"></div>
            <div class="skeleton-line short"></div>
            <div class="skeleton-line medium"></div>
          </div>
        </div>
      </div>

      <!-- CONTENT -->
      <div *ngIf="!loading">

        <!-- TAB BAR -->
        <div class="tab-bar">
          <button type="button" *ngFor="let tab of tabs" class="tab-btn" [class.active]="activeTab === tab.key" (click)="setTab(tab.key)">
            {{ tab.label }}
            <span class="tab-count">{{ tab.count }}</span>
          </button>
        </div>

        <!-- SEARCH -->
        <div class="search-row">
          <input type="text" class="search-input" placeholder="Filter by name, namespace, systemName…" [(ngModel)]="searchQuery" (ngModelChange)="onSearchChange()">
          <span class="search-count" *ngIf="searchQuery">{{ filteredProducts.length }} products, {{ filteredBackends.length }} backends</span>
        </div>

        <!-- PRODUCTS -->
        <div *ngIf="filteredProducts.length > 0">
          <div class="section-header-row">
            <h2 class="section-title">Products <span class="count-pill">{{ filteredProducts.length }}</span></h2>
            <div class="page-controls" *ngIf="productTotalPages > 1">
              <button class="page-btn" (click)="productPage = productPage - 1" [disabled]="productPage <= 1">&laquo; Prev</button>
              <span class="page-info">{{ productPage }} / {{ productTotalPages }}</span>
              <button class="page-btn" (click)="productPage = productPage + 1" [disabled]="productPage >= productTotalPages">Next &raquo;</button>
            </div>
          </div>
          <div class="product-grid">
            <article
              *ngFor="let product of pagedProducts; trackBy: trackByName"
              class="product-card card"
              [class.expanded]="expandedKey === productKey(product)"
              [class.source-api]="product.source === 'Admin API'"
              (click)="toggleExpand(product, $event)"
              (keydown.enter)="toggleExpand(product, $event)"
              tabindex="0"
              role="button"
              [attr.aria-expanded]="expandedKey === productKey(product)">
              <div class="card-top">
                <div class="title-row">
                  <h3 class="product-title">{{ product.name }}</h3>
                  <div class="badge-row">
                    <span class="badge" [class.badge-crd]="product.source === 'CRD'" [class.badge-api]="product.source.includes('Admin API')" [class.badge-merged]="product.source.includes('CRD + Admin API')">{{ product.source }}</span>
                    <span class="badge badge-ns">{{ product.backendNamespace || product.namespace }}</span>
                    <span class="badge badge-cluster" *ngIf="product.sourceCluster && product.sourceCluster !== 'local'">{{ product.sourceCluster }}</span>
                  </div>
                </div>
                <p class="system-name"><span class="meta-label">systemName</span> {{ product.systemName || '—' }}</p>
                <p class="desc">{{ product.description || 'No description' }}</p>
                <div class="card-meta">
                  <span class="pill pill-muted">{{ product.deploymentOption || 'N/A' }}</span>
                  <span class="pill pill-rules">{{ product.mappingRules.length > 0 ? product.mappingRules.length + ' rules' : 'OpenAPI auto-discover' }}</span>
                  <span class="pill pill-backends">{{ product.backendUsages.length }} backends</span>
                </div>
                <p class="expand-hint">{{ expandedKey === productKey(product) ? 'Click to collapse' : 'Click to expand details' }}</p>
              </div>

              <div *ngIf="expandedKey === productKey(product)" class="card-body" (click)="$event.stopPropagation()">
                <div class="panel">
                  <h4>Mapping Rules</h4>
                  <div class="table-wrap" *ngIf="product.mappingRules.length > 0">
                    <table class="data-table">
                      <thead>
                        <tr><th>Method</th><th>Pattern</th><th>Metric</th><th>Delta</th></tr>
                      </thead>
                      <tbody>
                        <tr *ngFor="let rule of product.mappingRules">
                          <td><span class="badge badge-method">{{ rule.httpMethod }}</span></td>
                          <td><code>{{ rule.pattern }}</code></td>
                          <td>{{ rule.metricRef }}</td>
                          <td>{{ rule.delta }}</td>
                        </tr>
                      </tbody>
                    </table>
                  </div>
                  <p *ngIf="product.mappingRules.length === 0" class="muted">No explicit mapping rules in 3scale. Paths will be auto-discovered from the backend's OpenAPI spec during migration.</p>
                </div>
                <div class="panel">
                  <h4>Backend Usages</h4>
                  <ul class="backend-list" *ngIf="product.backendUsages.length > 0">
                    <li *ngFor="let bu of product.backendUsages">
                      <strong>{{ bu.backendName }}</strong>
                      <span class="arrow">&rarr;</span>
                      <code>{{ bu.path }}</code>
                    </li>
                  </ul>
                  <p *ngIf="product.backendUsages.length === 0" class="muted">No backend usages.</p>
                </div>
                <div class="panel" *ngIf="product.authentication && hasAuth(product)">
                  <h4>Authentication</h4>
                  <pre class="auth-json"><code>{{ product.authentication | json }}</code></pre>
                </div>
              </div>
            </article>
          </div>
        </div>

        <!-- BACKENDS -->
        <div *ngIf="filteredBackends.length > 0" class="backends-section">
          <div class="section-header-row">
            <h2 class="section-title">Backends <span class="count-pill">{{ filteredBackends.length }}</span></h2>
            <div class="page-controls" *ngIf="backendTotalPages > 1">
              <button class="page-btn" (click)="backendPage = backendPage - 1" [disabled]="backendPage <= 1">&laquo; Prev</button>
              <span class="page-info">{{ backendPage }} / {{ backendTotalPages }}</span>
              <button class="page-btn" (click)="backendPage = backendPage + 1" [disabled]="backendPage >= backendTotalPages">Next &raquo;</button>
            </div>
          </div>
          <div class="backend-grid">
            <div *ngFor="let b of pagedBackends" class="backend-card card">
              <div class="backend-head">
                <strong>{{ b.name }}</strong>
                <span class="badge" [class.badge-crd]="b.source === 'CRD'" [class.badge-api]="b.source === 'Admin API'">{{ b.source }}</span>
              </div>
              <div class="backend-meta">
                <span *ngIf="b.namespace" class="pill pill-muted">{{ b.namespace }}</span>
                <span *ngIf="b.systemName" class="pill pill-muted">{{ b.systemName }}</span>
                <span *ngIf="b.privateEndpoint" class="pill pill-endpoint">{{ b.privateEndpoint }}</span>
              </div>
              <p *ngIf="b.description" class="backend-desc">{{ b.description }}</p>
            </div>
          </div>
        </div>

        <!-- EMPTY STATE -->
        <div *ngIf="filteredProducts.length === 0 && filteredBackends.length === 0" class="empty-state card">
          <div class="empty-illustration" aria-hidden="true"><span class="empty-icon">&#9671;</span></div>
          <h2>No products found</h2>
          <p *ngIf="activeTab === 'all'">No 3scale resources discovered from CRDs or Admin API.</p>
          <p *ngIf="activeTab === 'crd'">No CRD-based products found. Install the 3scale operator and create Products.</p>
          <p *ngIf="activeTab === 'admin-api'">No Admin API products found. Check the THREESCALE_ADMIN_URL and ACCESS_TOKEN configuration.</p>
          <a routerLink="/" class="btn">Back to Dashboard</a>
        </div>
      </div>
    </section>
  `,
  styles: [`
    .page-header {
      background: #151515; color: white; padding: 32px 0;
    }
    .page-header h1 {
      font-family: 'Red Hat Display', sans-serif; font-size: 1.75rem;
      font-weight: 700; margin: 0 0 8px;
    }
    .subtitle { margin: 0; color: #c9c9c9; max-width: 720px; line-height: 1.5; }
    .container { max-width: 1280px; margin: 0 auto; padding: 0 24px; }
    .header-row {
      display: flex; align-items: flex-start; justify-content: space-between;
      gap: 16px; flex-wrap: wrap;
    }
    .header-link {
      color: white; text-decoration: none; font-weight: 600;
      padding: 8px 16px; border: 1px solid rgba(255,255,255,0.35); border-radius: 6px; align-self: center;
    }
    .header-link:hover { background: rgba(255,255,255,0.08); text-decoration: none; }

    .status-bar { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 20px; }
    .status-pill {
      display: flex; align-items: center; gap: 8px; font-size: 0.82rem; font-weight: 600;
      padding: 6px 14px; border-radius: 999px; border: 1px solid rgba(255,255,255,0.2);
      background: rgba(255,255,255,0.06);
    }
    .status-dot {
      width: 8px; height: 8px; border-radius: 50%;
    }
    .status-pill.ok .status-dot { background: #3f9c35; box-shadow: 0 0 6px rgba(63,156,53,0.6); }
    .status-pill.off .status-dot { background: #6a6e73; }

    .stats-bar { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 16px; }
    .stat-pill {
      background: rgba(255,255,255,0.08); border: 1px solid rgba(255,255,255,0.15);
      border-radius: 8px; padding: 12px 20px; min-width: 120px;
    }
    .stat-pill.accent { border-color: rgba(238,0,0,0.45); background: rgba(238,0,0,0.12); }
    .stat-pill.api-pill { border-color: rgba(0,102,204,0.45); background: rgba(0,102,204,0.12); }
    .stat-value {
      display: block; font-family: 'Red Hat Display', sans-serif;
      font-size: 1.5rem; font-weight: 700;
    }
    .stat-label { font-size: 0.8rem; color: #c9c9c9; }

    .main-content { padding: 28px 0 48px; }

    .tab-bar { display: flex; gap: 4px; margin-bottom: 24px; border-bottom: 2px solid #d2d2d2; }
    .tab-btn {
      padding: 10px 20px; border: none; background: none; cursor: pointer;
      font-family: inherit; font-size: 0.9rem; font-weight: 600; color: #6a6e73;
      border-bottom: 3px solid transparent; margin-bottom: -2px; transition: all 0.15s;
    }
    .tab-btn.active { color: #ee0000; border-bottom-color: #ee0000; }
    .tab-btn:hover:not(.active) { color: #151515; }
    .tab-count {
      display: inline-block; margin-left: 6px; padding: 1px 8px; border-radius: 10px;
      background: #f5f5f5; font-size: 0.75rem; color: #6a6e73;
    }
    .tab-btn.active .tab-count { background: #ffecec; color: #a30000; }

    .section-title {
      font-family: 'Red Hat Display', sans-serif; font-size: 1.15rem; font-weight: 600;
      margin: 0 0 14px; color: #151515;
    }
    .count-pill {
      display: inline-block; background: #e8e8e8; color: #6a6e73; font-size: 0.78rem;
      padding: 2px 10px; border-radius: 10px; margin-left: 8px; font-weight: 500;
    }

    .card { background: white; border: 1px solid #d2d2d2; border-radius: 8px; transition: box-shadow 0.2s; }
    .card:hover { box-shadow: 0 4px 16px rgba(0,0,0,0.08); }

    .product-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(340px, 1fr)); gap: 16px; }
    .product-card { cursor: pointer; text-align: left; overflow: hidden; }
    .product-card:focus { outline: 2px solid #ee0000; outline-offset: 2px; }
    .product-card.expanded { grid-column: 1 / -1; box-shadow: 0 8px 24px rgba(0,0,0,0.1); }
    .product-card.source-api { border-left: 4px solid #0066cc; }

    .card-top { padding: 20px 22px 16px; }
    .title-row { display: flex; align-items: center; justify-content: space-between; gap: 10px; flex-wrap: wrap; }
    .product-title { font-family: 'Red Hat Display', sans-serif; font-size: 1.1rem; font-weight: 600; margin: 0; color: #151515; }
    .badge-row { display: flex; gap: 6px; flex-wrap: wrap; }
    .badge {
      display: inline-block; padding: 2px 10px; border-radius: 999px;
      font-size: 0.72rem; font-weight: 600;
    }
    .badge-ns { background: #e6f0ff; color: #0066cc; }
    .badge-crd { background: #e6f5e0; color: #2d6b24; }
    .badge-api { background: #fff4e6; color: #8a5500; }
    .badge-merged { background: #e8eaf6; color: #283593; }
    .badge-method { background: #f5f5f5; color: #151515; border: 1px solid #d2d2d2; }
    .badge-cluster { background: #fce4ec; color: #880e4f; }

    .system-name { margin: 10px 0 6px; font-size: 0.88rem; color: #6a6e73; }
    .meta-label { font-weight: 600; color: #151515; margin-right: 6px; }
    .desc { margin: 0 0 12px; color: #6a6e73; font-size: 0.9rem; line-height: 1.45; }
    .card-meta { display: flex; flex-wrap: wrap; gap: 8px; }
    .pill { font-size: 0.78rem; padding: 4px 10px; border-radius: 999px; background: #f5f5f5; color: #151515; border: 1px solid #d2d2d2; }
    .pill-muted { background: #f5f5f5; }
    .pill-rules { background: #fff4f4; border-color: rgba(238,0,0,0.25); color: #a30000; }
    .pill-backends { background: #e6f5e0; border-color: rgba(63,156,53,0.35); color: #2d6b24; }
    .pill-endpoint { background: #e6f0ff; border-color: rgba(0,102,204,0.25); color: #0066cc; font-family: monospace; font-size: 0.74rem; }

    .expand-hint { margin: 14px 0 0; font-size: 0.78rem; color: #ee0000; font-weight: 600; }

    .card-body { border-top: 1px solid #d2d2d2; padding: 20px 22px 24px; background: #fafafa; }
    .panel h4 { font-family: 'Red Hat Display', sans-serif; font-size: 0.95rem; margin: 0 0 12px; color: #151515; }
    .panel + .panel { margin-top: 20px; }
    .table-wrap { overflow-x: auto; }
    .data-table { width: 100%; border-collapse: collapse; font-size: 0.88rem; }
    .data-table th { background: #f5f5f5; text-align: left; padding: 10px 12px; border-bottom: 1px solid #d2d2d2; font-weight: 600; color: #151515; }
    .data-table td { padding: 10px 12px; border-bottom: 1px solid #d2d2d2; vertical-align: top; }
    .data-table code, .backend-list code { background: #1e1e1e; color: #d4d4d4; padding: 2px 8px; border-radius: 6px; font-size: 0.82em; }
    .backend-list { list-style: none; margin: 0; padding: 0; }
    .backend-list li { padding: 10px 12px; border: 1px solid #d2d2d2; border-radius: 8px; margin-bottom: 8px; background: white; display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
    .backend-list .arrow { color: #6a6e73; }
    .auth-json { margin: 0; background: #1e1e1e; color: #d4d4d4; padding: 14px; border-radius: 6px; overflow-x: auto; font-size: 0.82rem; }
    .muted { color: #6a6e73; font-size: 0.9rem; margin: 0; }

    .backends-section { margin-top: 32px; }
    .backend-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 14px; }
    .backend-card { padding: 18px 20px; }
    .backend-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 8px; }
    .backend-head strong { font-size: 0.95rem; color: #151515; }
    .backend-meta { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 6px; }
    .backend-desc { color: #6a6e73; font-size: 0.85rem; margin: 0; }

    .empty-state { text-align: center; padding: 48px 32px; max-width: 560px; margin: 0 auto; }
    .empty-state h2 { font-family: 'Red Hat Display', sans-serif; margin: 16px 0 8px; }
    .empty-state p { color: #6a6e73; line-height: 1.55; }
    .empty-illustration {
      width: 64px; height: 64px; margin: 0 auto; border-radius: 50%;
      background: #f5f5f5; border: 1px dashed #d2d2d2;
      display: flex; align-items: center; justify-content: center;
    }
    .empty-icon { font-size: 1.5rem; color: #6a6e73; }
    .btn {
      display: inline-block; margin-top: 20px; padding: 10px 22px;
      background: #ee0000; color: white; border: none; border-radius: 6px;
      font-weight: 600; text-decoration: none; cursor: pointer;
    }
    .btn:hover { background: #cc0000; text-decoration: none; color: white; }

    /* --- loading animation --- */
    .loading-state { padding: 16px 0 32px; }
    .loader-center { display: flex; flex-direction: column; align-items: center; margin-bottom: 28px; }
    .loading-stage { margin-top: 20px; color: #6a6e73; font-size: 0.92rem; animation: fadeText 2s ease-in-out infinite alternate; }

    .pulse-ring {
      width: 60px; height: 60px; border-radius: 50%;
      border: 4px solid transparent;
      border-top-color: #ee0000;
      border-right-color: #0066cc;
      animation: spin 1s linear infinite;
    }

    @keyframes spin { to { transform: rotate(360deg); } }
    @keyframes fadeText { 0% { opacity: 0.5; } 100% { opacity: 1; } }
    @keyframes shimmer { 0% { background-position: -400px 0; } 100% { background-position: 400px 0; } }

    .skeleton-grid-cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 12px; }
    .skeleton-card { padding: 20px; border-radius: 8px; background: white; border: 1px solid #e8e8e8; }
    .skeleton-line {
      height: 14px; border-radius: 4px; margin-bottom: 10px;
      background: linear-gradient(90deg, #e8e8e8 25%, #f5f5f5 50%, #e8e8e8 75%);
      background-size: 800px 100%; animation: shimmer 1.5s infinite;
    }
    .skeleton-line.long { width: 75%; }
    .skeleton-line.medium { width: 55%; }
    .skeleton-line.short { width: 40%; }

    .search-row {
      display: flex; align-items: center; gap: 12px; margin-bottom: 20px; flex-wrap: wrap;
    }
    .search-input {
      flex: 1; min-width: 240px; padding: 10px 16px; border: 1px solid #d2d2d2;
      border-radius: 6px; font-family: inherit; font-size: 0.92rem;
      transition: border-color 0.15s;
    }
    .search-input:focus { outline: none; border-color: #ee0000; box-shadow: 0 0 0 3px rgba(238,0,0,0.12); }
    .search-count { font-size: 0.82rem; color: #6a6e73; white-space: nowrap; }

    .section-header-row {
      display: flex; align-items: center; justify-content: space-between; gap: 12px;
      flex-wrap: wrap; margin-bottom: 14px;
    }
    .section-header-row .section-title { margin-bottom: 0; }
    .page-controls { display: flex; align-items: center; gap: 8px; }
    .page-btn {
      padding: 6px 14px; border: 1px solid #d2d2d2; border-radius: 4px;
      background: white; cursor: pointer; font-family: inherit; font-size: 0.82rem; font-weight: 600;
    }
    .page-btn:hover:not(:disabled) { border-color: #ee0000; color: #ee0000; }
    .page-btn:disabled { opacity: 0.4; cursor: not-allowed; }
    .page-info { font-size: 0.82rem; color: #6a6e73; min-width: 60px; text-align: center; }

    @media (max-width: 768px) {
      .product-card.expanded { grid-column: auto; }
    }
  `]
})
export class ThreeScaleExplorerComponent implements OnInit {
  products: ThreeScaleProduct[] = [];
  backends: ThreeScaleBackend[] = [];
  status: ThreeScaleStatus | null = null;
  loading = true;
  loadingStage = 'Connecting to cluster...';
  expandedKey: string | null = null;
  activeTab: SourceTab = 'all';
  searchQuery = '';
  productPage = 1;
  backendPage = 1;
  pageSize = 24;

  constructor(private api: ApiService) {}

  get crdCount(): number { return this.products.filter(p => p.source?.includes('CRD')).length; }
  get apiCount(): number { return this.products.filter(p => p.source?.includes('Admin API')).length; }
  get totalMappingRules(): number { return this.products.reduce((n, p) => n + (p.mappingRules?.length || 0), 0); }

  private isCrd(source?: string): boolean { return !!source && source.includes('CRD'); }
  private isApi(source?: string): boolean { return !!source && source.includes('Admin API'); }

  private matchesSearch(text: string): boolean {
    if (!this.searchQuery) return true;
    const q = this.searchQuery.toLowerCase();
    return text.toLowerCase().includes(q);
  }

  get filteredProducts(): ThreeScaleProduct[] {
    let items = this.products;
    if (this.activeTab === 'crd') items = items.filter(p => this.isCrd(p.source));
    else if (this.activeTab === 'admin-api') items = items.filter(p => this.isApi(p.source));
    if (this.searchQuery) {
      items = items.filter(p =>
        this.matchesSearch(p.name) || this.matchesSearch(p.systemName || '') ||
        this.matchesSearch(p.namespace || '') || this.matchesSearch(p.backendNamespace || '') ||
        this.matchesSearch(p.description || '')
      );
    }
    return items;
  }

  get filteredBackends(): ThreeScaleBackend[] {
    let items = this.backends;
    if (this.activeTab === 'crd') items = items.filter(b => this.isCrd(b.source));
    else if (this.activeTab === 'admin-api') items = items.filter(b => this.isApi(b.source));
    if (this.searchQuery) {
      items = items.filter(b =>
        this.matchesSearch(b.name) || this.matchesSearch(b.systemName || '') ||
        this.matchesSearch(b.namespace || '') || this.matchesSearch(b.privateEndpoint || '') ||
        this.matchesSearch(b.description || '')
      );
    }
    return items;
  }

  get productTotalPages(): number { return Math.max(1, Math.ceil(this.filteredProducts.length / this.pageSize)); }
  get backendTotalPages(): number { return Math.max(1, Math.ceil(this.filteredBackends.length / this.pageSize)); }

  get pagedProducts(): ThreeScaleProduct[] {
    const start = (this.productPage - 1) * this.pageSize;
    return this.filteredProducts.slice(start, start + this.pageSize);
  }

  get pagedBackends(): ThreeScaleBackend[] {
    const start = (this.backendPage - 1) * this.pageSize;
    return this.filteredBackends.slice(start, start + this.pageSize);
  }

  onSearchChange(): void {
    this.productPage = 1;
    this.backendPage = 1;
  }

  get tabs(): { key: SourceTab; label: string; count: number }[] {
    return [
      { key: 'all', label: 'All Sources', count: this.products.length + this.backends.length },
      { key: 'crd', label: 'CRDs', count: this.products.filter(p => this.isCrd(p.source)).length + this.backends.filter(b => this.isCrd(b.source)).length },
      { key: 'admin-api', label: 'Admin API', count: this.products.filter(p => this.isApi(p.source)).length + this.backends.filter(b => this.isApi(b.source)).length }
    ];
  }

  ngOnInit(): void {
    this.loadingStage = 'Checking 3scale connectivity...';
    this.api.getThreeScaleStatus().subscribe({
      next: (s) => {
        this.status = s;
        this.loadingStage = 'Discovering Products and Backends...';
        this.loadData();
      },
      error: () => {
        this.loadingStage = 'Discovering Products and Backends...';
        this.loadData();
      }
    });
  }

  private loadData(): void {
    forkJoin({
      products: this.api.getProducts(),
      backends: this.api.getBackends()
    }).subscribe({
      next: ({ products, backends }) => {
        this.products = products;
        this.backends = backends;
        this.loading = false;
      },
      error: () => { this.loading = false; }
    });
  }

  setTab(tab: SourceTab): void { this.activeTab = tab; this.productPage = 1; this.backendPage = 1; }

  productKey(p: ThreeScaleProduct): string { return `${p.source}/${p.namespace}/${p.name}`; }

  toggleExpand(product: ThreeScaleProduct, event?: Event): void {
    if (event) event.preventDefault();
    const key = this.productKey(product);
    this.expandedKey = this.expandedKey === key ? null : key;
  }

  trackByName = (_: number, p: ThreeScaleProduct): string => this.productKey(p);

  hasAuth(product: ThreeScaleProduct): boolean {
    return product.authentication != null && Object.keys(product.authentication).length > 0;
  }
}

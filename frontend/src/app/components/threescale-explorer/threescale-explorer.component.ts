import { Component, CUSTOM_ELEMENTS_SCHEMA, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { ApiService, ThreeScaleProduct } from '../../services/api.service';

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
            <p class="subtitle">Products, mapping rules, and backend usages discovered from 3scale CRDs in your cluster.</p>
          </div>
          <a routerLink="/" class="header-link">Dashboard</a>
        </div>
        <div class="stats-bar" *ngIf="!loading">
          <div class="stat-pill">
            <span class="stat-value">{{ products.length }}</span>
            <span class="stat-label">Products</span>
          </div>
          <div class="stat-pill">
            <span class="stat-value">{{ backendCount }}</span>
            <span class="stat-label">Backends</span>
          </div>
          <div class="stat-pill accent">
            <span class="stat-value">{{ totalMappingRules }}</span>
            <span class="stat-label">Mapping Rules total</span>
          </div>
        </div>
      </div>
    </header>

    <section class="container main-content">
      <div *ngIf="loading" class="loading-state">
        <div class="skeleton-grid">
          <div *ngFor="let i of [1,2,3]" class="skeleton-stat"></div>
        </div>
        <div class="skeleton-bar"></div>
        <div class="skeleton-grid-cards">
          <div *ngFor="let i of [1,2,3,4,5,6]" class="skeleton-card">
            <div class="skeleton-line long"></div>
            <div class="skeleton-line short"></div>
            <div class="skeleton-line medium"></div>
          </div>
        </div>
        <p class="loading-text">Loading 3scale products…</p>
      </div>

      <div *ngIf="!loading && products.length === 0" class="empty-state card">
        <div class="empty-illustration" aria-hidden="true">
          <span class="empty-icon">◇</span>
        </div>
        <h2>No products found</h2>
        <p>We did not find any 3scale Product CRDs in the cluster. Install the 3scale operator, create Products, then refresh this page.</p>
        <p class="hint">Tip: confirm your GateForge backend has access to the namespaces where 3scale resources live.</p>
        <a routerLink="/" class="btn">Back to dashboard</a>
      </div>

      <div *ngIf="!loading && products.length > 0" class="product-grid">
        <article
          *ngFor="let product of products; trackBy: trackByName"
          class="product-card card"
          [class.expanded]="expandedKey === productKey(product)"
          (click)="toggleExpand(product, $event)"
          (keydown.enter)="toggleExpand(product, $event)"
          (keydown.space)="$event.preventDefault(); toggleExpand(product, $event)"
          tabindex="0"
          role="button"
          [attr.aria-expanded]="expandedKey === productKey(product)">
          <div class="card-top">
            <div class="title-row">
              <h2 class="product-title">{{ product.name }}</h2>
              <span class="badge badge-ns">{{ product.namespace }}</span>
            </div>
            <p class="system-name"><span class="meta-label">systemName</span> {{ product.systemName || '—' }}</p>
            <p class="desc">{{ product.description || 'No description' }}</p>
            <div class="card-meta">
              <span class="pill pill-muted">Deployment: {{ product.deploymentOption || 'N/A' }}</span>
              <span class="pill pill-rules">{{ product.mappingRules.length }} rules</span>
              <span class="pill pill-backends">{{ product.backendUsages.length }} backends</span>
            </div>
            <p class="expand-hint">{{ expandedKey === productKey(product) ? 'Click to collapse' : 'Click to expand details' }}</p>
          </div>

          <div *ngIf="expandedKey === productKey(product)" class="card-body" (click)="$event.stopPropagation()">
            <div class="panel">
              <h3>Mapping rules</h3>
              <div class="table-wrap" *ngIf="product.mappingRules.length > 0">
                <table class="data-table">
                  <thead>
                    <tr>
                      <th>Method</th>
                      <th>Pattern</th>
                      <th>Metric</th>
                      <th>Delta</th>
                    </tr>
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
              <p *ngIf="product.mappingRules.length === 0" class="muted">No mapping rules on this product.</p>
            </div>
            <div class="panel">
              <h3>Backend usages</h3>
              <ul class="backend-list" *ngIf="product.backendUsages.length > 0">
                <li *ngFor="let bu of product.backendUsages">
                  <strong>{{ bu.backendName }}</strong>
                  <span class="arrow">→</span>
                  <code>{{ bu.path }}</code>
                </li>
              </ul>
              <p *ngIf="product.backendUsages.length === 0" class="muted">No backend usages linked.</p>
            </div>
          </div>
        </article>
      </div>
    </section>
  `,
  styles: [`
    .page-header {
      background: #151515;
      color: white;
      padding: 32px 0;
    }
    .page-header h1 {
      font-family: 'Red Hat Display', sans-serif;
      font-size: 1.75rem;
      font-weight: 700;
      margin: 0 0 8px;
    }
    .subtitle {
      margin: 0;
      color: #c9c9c9;
      max-width: 720px;
      line-height: 1.5;
    }
    .container {
      max-width: 1280px;
      margin: 0 auto;
      padding: 0 24px;
    }
    .header-row {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 16px;
      flex-wrap: wrap;
    }
    .header-link {
      color: white;
      text-decoration: none;
      font-weight: 600;
      padding: 8px 16px;
      border: 1px solid rgba(255,255,255,0.35);
      border-radius: 6px;
      align-self: center;
    }
    .header-link:hover {
      background: rgba(255,255,255,0.08);
      text-decoration: none;
    }
    .stats-bar {
      display: flex;
      flex-wrap: wrap;
      gap: 12px;
      margin-top: 24px;
    }
    .stat-pill {
      background: rgba(255,255,255,0.08);
      border: 1px solid rgba(255,255,255,0.15);
      border-radius: 8px;
      padding: 12px 20px;
      min-width: 140px;
    }
    .stat-pill.accent {
      border-color: rgba(238,0,0,0.45);
      background: rgba(238,0,0,0.12);
    }
    .stat-value {
      display: block;
      font-family: 'Red Hat Display', sans-serif;
      font-size: 1.5rem;
      font-weight: 700;
    }
    .stat-label {
      font-size: 0.8rem;
      color: #c9c9c9;
    }
    .main-content {
      padding: 32px 0 48px;
    }
    .card {
      background: white;
      border: 1px solid #d2d2d2;
      border-radius: 8px;
      transition: box-shadow 0.2s;
    }
    .card:hover {
      box-shadow: 0 4px 16px rgba(0,0,0,0.08);
    }
    .product-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
      gap: 16px;
    }
    .product-card {
      cursor: pointer;
      text-align: left;
      overflow: hidden;
    }
    .product-card:focus {
      outline: 2px solid #ee0000;
      outline-offset: 2px;
    }
    .product-card.expanded {
      grid-column: 1 / -1;
      box-shadow: 0 8px 24px rgba(0,0,0,0.1);
    }
    .card-top {
      padding: 20px 22px 16px;
    }
    .title-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 10px;
      flex-wrap: wrap;
    }
    .product-title {
      font-family: 'Red Hat Display', sans-serif;
      font-size: 1.1rem;
      font-weight: 600;
      margin: 0;
      color: #151515;
    }
    .badge {
      display: inline-block;
      padding: 2px 10px;
      border-radius: 999px;
      font-size: 0.72rem;
      font-weight: 600;
    }
    .badge-ns {
      background: #e6f0ff;
      color: #0066cc;
    }
    .badge-method {
      background: #f5f5f5;
      color: #151515;
      border: 1px solid #d2d2d2;
    }
    .system-name {
      margin: 10px 0 6px;
      font-size: 0.88rem;
      color: #6a6e73;
    }
    .meta-label {
      font-weight: 600;
      color: #151515;
      margin-right: 6px;
    }
    .desc {
      margin: 0 0 12px;
      color: #6a6e73;
      font-size: 0.9rem;
      line-height: 1.45;
    }
    .card-meta {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
    .pill {
      font-size: 0.78rem;
      padding: 4px 10px;
      border-radius: 999px;
      background: #f5f5f5;
      color: #151515;
      border: 1px solid #d2d2d2;
    }
    .pill-rules { background: #fff4f4; border-color: rgba(238,0,0,0.25); color: #a30000; }
    .pill-backends { background: #e6f5e0; border-color: rgba(63,156,53,0.35); color: #2d6b24; }
    .expand-hint {
      margin: 14px 0 0;
      font-size: 0.78rem;
      color: #ee0000;
      font-weight: 600;
    }
    .card-body {
      border-top: 1px solid #d2d2d2;
      padding: 20px 22px 24px;
      background: #fafafa;
    }
    .panel h3 {
      font-family: 'Red Hat Display', sans-serif;
      font-size: 0.95rem;
      margin: 0 0 12px;
      color: #151515;
    }
    .panel + .panel {
      margin-top: 20px;
    }
    .table-wrap { overflow-x: auto; }
    .data-table {
      width: 100%;
      border-collapse: collapse;
      font-size: 0.88rem;
    }
    .data-table th {
      background: #f5f5f5;
      text-align: left;
      padding: 10px 12px;
      border-bottom: 1px solid #d2d2d2;
      font-weight: 600;
      color: #151515;
    }
    .data-table td {
      padding: 10px 12px;
      border-bottom: 1px solid #d2d2d2;
      vertical-align: top;
    }
    .data-table code {
      background: #1e1e1e;
      color: #d4d4d4;
      padding: 2px 8px;
      border-radius: 6px;
      font-size: 0.82em;
    }
    .backend-list {
      list-style: none;
      margin: 0;
      padding: 0;
    }
    .backend-list li {
      padding: 10px 12px;
      border: 1px solid #d2d2d2;
      border-radius: 8px;
      margin-bottom: 8px;
      background: white;
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px;
    }
    .backend-list .arrow { color: #6a6e73; }
    .backend-list code {
      background: #1e1e1e;
      color: #d4d4d4;
      padding: 2px 8px;
      border-radius: 6px;
      font-size: 0.85em;
    }
    .muted { color: #6a6e73; font-size: 0.9rem; margin: 0; }
    .empty-state {
      text-align: center;
      padding: 48px 32px;
      max-width: 560px;
      margin: 0 auto;
    }
    .empty-state h2 {
      font-family: 'Red Hat Display', sans-serif;
      margin: 16px 0 8px;
    }
    .empty-state p { color: #6a6e73; line-height: 1.55; }
    .hint { font-size: 0.88rem; }
    .empty-illustration {
      width: 64px;
      height: 64px;
      margin: 0 auto;
      border-radius: 50%;
      background: #f5f5f5;
      border: 1px dashed #d2d2d2;
      display: flex;
      align-items: center;
      justify-content: center;
    }
    .empty-icon { font-size: 1.5rem; color: #6a6e73; }
    .btn {
      display: inline-block;
      margin-top: 20px;
      padding: 10px 22px;
      background: #ee0000;
      color: white;
      border: none;
      border-radius: 6px;
      font-weight: 600;
      text-decoration: none;
      cursor: pointer;
    }
    .btn:hover { background: #cc0000; text-decoration: none; color: white; }
    @keyframes shimmer {
      0% { background-position: -400px 0; }
      100% { background-position: 400px 0; }
    }
    .loading-state { padding: 8px 0 32px; }
    .loading-text { text-align: center; color: #6a6e73; margin-top: 24px; }
    .skeleton-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin-bottom: 24px; }
    .skeleton-stat {
      height: 72px; border-radius: 8px;
      background: linear-gradient(90deg, #e8e8e8 25%, #f5f5f5 50%, #e8e8e8 75%);
      background-size: 800px 100%; animation: shimmer 1.5s infinite;
    }
    .skeleton-bar {
      height: 36px; border-radius: 6px; margin-bottom: 16px;
      background: linear-gradient(90deg, #e8e8e8 25%, #f5f5f5 50%, #e8e8e8 75%);
      background-size: 800px 100%; animation: shimmer 1.5s infinite;
    }
    .skeleton-grid-cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 12px; }
    .skeleton-card {
      padding: 20px; border-radius: 8px; background: white; border: 1px solid #e8e8e8;
    }
    .skeleton-line {
      height: 14px; border-radius: 4px; margin-bottom: 10px;
      background: linear-gradient(90deg, #e8e8e8 25%, #f5f5f5 50%, #e8e8e8 75%);
      background-size: 800px 100%; animation: shimmer 1.5s infinite;
    }
    .skeleton-line.long { width: 75%; }
    .skeleton-line.medium { width: 55%; }
    .skeleton-line.short { width: 40%; }
    @media (max-width: 768px) {
      .skeleton-grid { grid-template-columns: 1fr; }
      .product-card.expanded { grid-column: auto; }
    }
  `]
})
export class ThreeScaleExplorerComponent implements OnInit {
  products: ThreeScaleProduct[] = [];
  backendCount = 0;
  loading = true;
  expandedKey: string | null = null;

  constructor(private api: ApiService) {}

  get totalMappingRules(): number {
    return this.products.reduce((n, p) => n + (p.mappingRules?.length || 0), 0);
  }

  ngOnInit(): void {
    forkJoin({
      products: this.api.getProducts(),
      backends: this.api.getBackends()
    }).subscribe({
      next: ({ products, backends }) => {
        this.products = products;
        this.backendCount = Array.isArray(backends) ? backends.length : 0;
        this.loading = false;
      },
      error: () => {
        this.loading = false;
      }
    });
  }

  productKey(p: ThreeScaleProduct): string {
    return `${p.namespace}/${p.name}`;
  }

  toggleExpand(product: ThreeScaleProduct, event?: Event): void {
    if (event) event.preventDefault();
    const key = this.productKey(product);
    this.expandedKey = this.expandedKey === key ? null : key;
  }

  trackByName(_: number, p: ThreeScaleProduct): string {
    return this.productKey(p);
  }
}

import { Component, CUSTOM_ELEMENTS_SCHEMA, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService, ThreeScaleProduct, MigrationPlan, ApplyResult } from '../../services/api.service';

@Component({
  selector: 'app-migration-wizard',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  schemas: [CUSTOM_ELEMENTS_SCHEMA],
  template: `
    <header class="page-header">
      <div class="container">
        <div class="header-row">
          <div>
            <h1>Migration Wizard</h1>
            <p class="subtitle">Select 3scale products, choose a gateway strategy, and review generated Connectivity Link resources.</p>
          </div>
          <a routerLink="/" class="header-link">Dashboard</a>
        </div>
      </div>
    </header>

    <section class="container main-content">
      <nav class="steps" aria-label="Wizard steps">
        <div class="step-track">
          <ng-container *ngFor="let s of stepLabels; let i = index">
            <div class="step-node-wrap">
              <div
                class="step-node"
                [class.active]="step === i + 1"
                [class.done]="step > i + 1">
                <span>{{ i + 1 }}</span>
              </div>
              <span class="step-caption" [class.active]="step === i + 1">{{ s }}</span>
            </div>
            <div *ngIf="i < stepLabels.length - 1" class="step-connector" [class.active]="step > i + 1"></div>
          </ng-container>
        </div>
      </nav>

      <div *ngIf="productsLoading" class="loading-state">
        <div class="skeleton-bar wide"></div>
        <div class="skeleton-grid-cards">
          <div *ngFor="let i of [1,2,3,4,5,6]" class="skeleton-card">
            <div class="skeleton-line long"></div>
            <div class="skeleton-line short"></div>
          </div>
        </div>
        <p class="loading-text">Loading products from cluster…</p>
      </div>

      <div *ngIf="!productsLoading && step === 1" class="step-panel">
        <div class="step-head">
          <h2>Select products</h2>
          <span class="count-badge" *ngIf="selectedCount > 0">{{ selectedCount }} selected</span>
        </div>
        <p class="step-desc">Choose one or more 3scale products to include in the migration analysis.</p>

        <div *ngIf="products.length === 0" class="empty-inline card">
          <p>No products were found. Explore the cluster in <a routerLink="/threescale">3scale Explorer</a> or verify CRDs.</p>
        </div>

        <div *ngIf="products.length > 0" class="select-grid">
          <label *ngFor="let p of products" class="select-card card" [class.checked]="p.selected">
            <input type="checkbox" class="visually-hidden" [(ngModel)]="p.selected" (click)="$event.stopPropagation()">
            <div class="check-corner" [class.on]="p.selected"></div>
            <div class="select-body">
              <div class="select-title-row">
                <span class="product-name">{{ p.product.name }}</span>
                <span class="badge badge-ns">{{ p.product.namespace }}</span>
              </div>
              <div class="select-stats">
                <span class="pill">{{ p.product.mappingRules.length }} rules</span>
                <span class="pill pill-green">{{ p.product.backendUsages.length }} backends</span>
              </div>
            </div>
          </label>
        </div>

        <div class="actions">
          <button type="button" class="btn-primary" (click)="step = 2" [disabled]="selectedCount === 0">Next</button>
        </div>
      </div>

      <div *ngIf="!productsLoading && step === 2" class="step-panel">
        <h2>Gateway strategy</h2>
        <p class="step-desc">Pick how Gateways should be shaped after migration. You can re-run analysis if plans change.</p>

        <div class="strategy-grid">
          <label *ngFor="let s of strategies" class="strategy-card card" [class.selected]="gatewayStrategy === s.value">
            <input type="radio" class="visually-hidden" name="gatewayStrategy" [value]="s.value" [(ngModel)]="gatewayStrategy">
            <div class="strategy-icon" aria-hidden="true">{{ s.icon }}</div>
            <div class="strategy-text">
              <span class="strategy-title">{{ s.label }}</span>
              <p>{{ s.description }}</p>
            </div>
          </label>
        </div>

        <div class="actions">
          <button type="button" class="btn-secondary" (click)="step = 1">Back</button>
          <button type="button" class="btn-primary" (click)="analyze()" [disabled]="analyzing">
            {{ analyzing ? 'Analyzing…' : 'Analyze' }}
          </button>
        </div>
      </div>

      <div *ngIf="!productsLoading && step === 3 && plan" class="step-panel">
        <h2>Review plan</h2>
        <p class="step-desc">Confirm generated resources before applying them to your cluster workflows.</p>

        <div class="info-banner">
          <div>
            <span class="banner-label">Plan ID</span>
            <strong class="banner-id">{{ plan.id }}</strong>
          </div>
          <div class="banner-meta">
            <span class="pill pill-strategy">Strategy: {{ plan.gatewayStrategy }}</span>
            <span class="pill pill-muted">Products: {{ plan.sourceProducts.join(', ') || '—' }}</span>
          </div>
        </div>

        <div class="resources-grid">
          <div *ngFor="let res of plan.resources; let idx = index" class="resource-card card">
            <button type="button" class="resource-head" (click)="toggleYaml(idx)" [attr.aria-expanded]="yamlOpen[idx]">
              <span class="badge badge-kind">{{ res.kind }}</span>
              <div class="resource-ident">
                <span class="res-name">{{ res.name }}</span>
                <span class="res-ns">{{ res.namespace }}</span>
              </div>
              <span class="chevron" [class.open]="yamlOpen[idx]">⌄</span>
            </button>
            <div *ngIf="yamlOpen[idx]" class="resource-yaml">
              <pre><code>{{ res.yaml }}</code></pre>
            </div>
          </div>
        </div>

        <div *ngIf="applyResult" class="apply-result" [class.success]="applyResult.failed === 0" [class.partial]="applyResult.failed > 0">
          <div class="apply-summary">
            <strong *ngIf="applyResult.failed === 0">Migration applied successfully!</strong>
            <strong *ngIf="applyResult.failed > 0">Migration completed with errors</strong>
            <span class="apply-counts">
              {{ applyResult.applied }} applied, {{ applyResult.failed }} failed
            </span>
          </div>
          <div class="apply-details">
            <div *ngFor="let r of applyResult.results" class="apply-row" [class.err]="!r.success">
              <span class="badge badge-kind">{{ r.kind }}</span>
              <span class="apply-name">{{ r.name }}</span>
              <span class="apply-status" [class.ok]="r.success" [class.fail]="!r.success">
                {{ r.success ? 'Applied' : r.message }}
              </span>
            </div>
          </div>
        </div>

        <div class="actions">
          <button type="button" class="btn-secondary" (click)="step = 2">Back</button>
          <button type="button" class="btn-apply" (click)="applyMigration()" [disabled]="applying || !!applyResult">
            {{ applying ? 'Applying…' : (applyResult ? 'Applied' : 'Apply to Cluster') }}
          </button>
        </div>
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
      max-width: 760px;
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
    .header-link:hover { background: rgba(255,255,255,0.08); text-decoration: none; }
    .main-content { padding: 28px 0 56px; }
    .steps { margin-bottom: 28px; }
    .step-track {
      display: flex;
      align-items: flex-start;
      justify-content: center;
      flex-wrap: wrap;
      gap: 8px 0;
    }
    .step-node-wrap {
      display: flex;
      flex-direction: column;
      align-items: center;
      min-width: 96px;
    }
    .step-node {
      width: 40px;
      height: 40px;
      border-radius: 50%;
      border: 2px solid #d2d2d2;
      display: flex;
      align-items: center;
      justify-content: center;
      font-family: 'Red Hat Display', sans-serif;
      font-weight: 700;
      color: #6a6e73;
      background: white;
    }
    .step-node.active {
      border-color: #ee0000;
      background: #ee0000;
      color: white;
      box-shadow: 0 0 0 4px rgba(238,0,0,0.2);
    }
    .step-node.done {
      border-color: #3f9c35;
      background: #3f9c35;
      color: white;
    }
    .step-caption {
      margin-top: 8px;
      font-size: 0.78rem;
      color: #6a6e73;
      text-align: center;
      max-width: 120px;
      line-height: 1.3;
    }
    .step-caption.active { color: #ee0000; font-weight: 600; }
    .step-connector {
      width: 48px;
      height: 2px;
      background: #d2d2d2;
      margin: 19px 4px 0;
      flex-shrink: 0;
    }
    .step-connector.active { background: #3f9c35; }
    @media (max-width: 640px) {
      .step-connector { display: none; }
      .step-track { gap: 16px; }
    }
    .step-panel h2 {
      font-family: 'Red Hat Display', sans-serif;
      font-size: 1.35rem;
      margin: 0 0 8px;
      color: #151515;
    }
    .step-desc { color: #6a6e73; margin: 0 0 20px; max-width: 720px; line-height: 1.5; }
    .step-head { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 4px; }
    .count-badge {
      display: inline-block;
      background: #ee0000;
      color: white;
      font-size: 0.78rem;
      font-weight: 600;
      padding: 4px 12px;
      border-radius: 999px;
    }
    .card {
      background: white;
      border: 1px solid #d2d2d2;
      border-radius: 8px;
      transition: box-shadow 0.2s;
    }
    .card:hover { box-shadow: 0 4px 16px rgba(0,0,0,0.06); }
    .empty-inline { padding: 20px 24px; color: #6a6e73; }
    .empty-inline a { color: #0066cc; font-weight: 600; }
    .select-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
      gap: 14px;
      margin-bottom: 24px;
    }
    .select-card {
      position: relative;
      display: block;
      cursor: pointer;
      padding: 0;
      overflow: hidden;
    }
    .select-card.checked {
      border-color: #ee0000;
      box-shadow: 0 4px 16px rgba(238,0,0,0.12);
    }
    .visually-hidden {
      position: absolute;
      width: 1px; height: 1px;
      padding: 0; margin: -1px;
      overflow: hidden; clip: rect(0,0,0,0);
      border: 0;
    }
    .check-corner {
      position: absolute;
      top: 12px;
      right: 12px;
      width: 22px;
      height: 22px;
      border-radius: 6px;
      border: 2px solid #d2d2d2;
      background: white;
    }
    .check-corner.on {
      border-color: #ee0000;
      background: #ee0000;
      box-shadow: inset 0 0 0 3px white;
    }
    .select-body { padding: 18px 20px 16px; }
    .select-title-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
      margin-bottom: 10px;
    }
    .product-name {
      font-weight: 600;
      color: #151515;
      font-size: 0.95rem;
    }
    .badge {
      display: inline-block;
      padding: 2px 10px;
      border-radius: 999px;
      font-size: 0.72rem;
      font-weight: 600;
    }
    .badge-ns { background: #e6f0ff; color: #0066cc; }
    .badge-kind { background: #f5f5f5; color: #151515; border: 1px solid #d2d2d2; }
    .select-stats { display: flex; flex-wrap: wrap; gap: 8px; }
    .pill {
      font-size: 0.78rem;
      padding: 4px 10px;
      border-radius: 999px;
      background: #f5f5f5;
      border: 1px solid #d2d2d2;
      color: #151515;
    }
    .pill-green { background: #e6f5e0; border-color: rgba(63,156,53,0.35); color: #2d6b24; }
    .strategy-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
      gap: 14px;
      margin-bottom: 24px;
    }
    .strategy-card {
      display: flex;
      gap: 14px;
      padding: 18px 18px 16px;
      cursor: pointer;
      align-items: flex-start;
    }
    .strategy-card.selected {
      border-color: #ee0000;
      box-shadow: 0 4px 18px rgba(238,0,0,0.12);
    }
    .strategy-icon {
      width: 44px;
      height: 44px;
      border-radius: 10px;
      background: #f5f5f5;
      border: 1px solid #d2d2d2;
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 1.35rem;
      flex-shrink: 0;
    }
    .strategy-title {
      font-family: 'Red Hat Display', sans-serif;
      font-weight: 600;
      color: #151515;
      display: block;
      margin-bottom: 6px;
    }
    .strategy-text p {
      margin: 0;
      font-size: 0.88rem;
      color: #6a6e73;
      line-height: 1.45;
    }
    .info-banner {
      background: #e6f0ff;
      border: 1px solid rgba(0,102,204,0.35);
      border-radius: 8px;
      padding: 16px 20px;
      margin-bottom: 22px;
      display: flex;
      flex-wrap: wrap;
      gap: 16px 24px;
      align-items: center;
    }
    .banner-label { display: block; font-size: 0.75rem; color: #6a6e73; text-transform: uppercase; letter-spacing: 0.04em; }
    .banner-id { font-size: 1.05rem; color: #0066cc; }
    .banner-meta { display: flex; flex-wrap: wrap; gap: 8px; }
    .pill-strategy { background: white; border-color: #0066cc; color: #0066cc; font-weight: 600; }
    .pill-muted { background: white; color: #6a6e73; }
    .resources-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
      gap: 14px;
      margin-bottom: 24px;
    }
    .resource-card { overflow: hidden; padding: 0; }
    .resource-head {
      width: 100%;
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 14px 16px;
      border: none;
      background: white;
      cursor: pointer;
      text-align: left;
      font: inherit;
    }
    .resource-head:hover { background: #fafafa; }
    .resource-ident { flex: 1; min-width: 0; }
    .res-name { display: block; font-weight: 600; color: #151515; word-break: break-all; }
    .res-ns { font-size: 0.82rem; color: #6a6e73; }
    .chevron {
      font-size: 1.2rem;
      color: #6a6e73;
      transition: transform 0.2s;
      line-height: 1;
    }
    .chevron.open { transform: rotate(180deg); }
    .resource-yaml {
      border-top: 1px solid #d2d2d2;
      background: #fafafa;
      padding: 12px 14px 16px;
    }
    .resource-yaml pre {
      margin: 0;
      background: #1e1e1e;
      color: #d4d4d4;
      padding: 14px;
      border-radius: 6px;
      overflow-x: auto;
      font-size: 0.82rem;
    }
    .apply-result {
      border-radius: 8px; padding: 16px 20px; margin-bottom: 22px;
    }
    .apply-result.success { background: #e6f5e0; border: 1px solid #3f9c35; }
    .apply-result.partial { background: #fef3cd; border: 1px solid #d4a017; }
    .apply-summary { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
    .apply-summary strong { font-size: 1rem; }
    .apply-counts { font-size: 0.85rem; color: #6a6e73; }
    .apply-details { display: flex; flex-direction: column; gap: 6px; }
    .apply-row { display: flex; align-items: center; gap: 10px; font-size: 0.88rem; padding: 4px 0; }
    .apply-row.err { color: #c9190b; }
    .apply-name { flex: 1; font-weight: 500; }
    .apply-status.ok { color: #3f9c35; font-weight: 600; }
    .apply-status.fail { color: #c9190b; font-size: 0.82rem; }
    .btn-apply {
      padding: 10px 22px; border-radius: 6px; font-weight: 600; cursor: pointer;
      border: none; font-family: inherit; font-size: 0.95rem;
      background: #3f9c35; color: white;
    }
    .btn-apply:hover:not(:disabled) { background: #2d6b24; }
    .btn-apply:disabled { opacity: 0.5; cursor: not-allowed; }
    .actions { display: flex; gap: 12px; flex-wrap: wrap; }
    .btn-primary, .btn-secondary {
      padding: 10px 22px;
      border-radius: 6px;
      font-weight: 600;
      cursor: pointer;
      border: none;
      font-family: inherit;
      font-size: 0.95rem;
    }
    .btn-primary {
      background: #ee0000;
      color: white;
    }
    .btn-primary:hover:not(:disabled) { background: #cc0000; }
    .btn-primary:disabled { opacity: 0.5; cursor: not-allowed; }
    .btn-secondary {
      background: white;
      color: #151515;
      border: 1px solid #d2d2d2;
    }
    .btn-secondary:hover { border-color: #151515; }
    @keyframes shimmer {
      0% { background-position: -400px 0; }
      100% { background-position: 400px 0; }
    }
    .loading-state { padding: 8px 0 24px; }
    .loading-text { text-align: center; color: #6a6e73; margin-top: 20px; }
    .skeleton-bar.wide {
      height: 40px; border-radius: 6px; margin-bottom: 18px;
      background: linear-gradient(90deg, #e8e8e8 25%, #f5f5f5 50%, #e8e8e8 75%);
      background-size: 800px 100%; animation: shimmer 1.5s infinite;
    }
    .skeleton-grid-cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 12px; }
    .skeleton-card {
      padding: 20px; border-radius: 8px; background: white; border: 1px solid #e8e8e8;
    }
    .skeleton-line {
      height: 14px; border-radius: 4px; margin-bottom: 10px;
      background: linear-gradient(90deg, #e8e8e8 25%, #f5f5f5 50%, #e8e8e8 75%);
      background-size: 800px 100%; animation: shimmer 1.5s infinite;
    }
    .skeleton-line.long { width: 75%; }
    .skeleton-line.short { width: 42%; }
  `]
})
export class MigrationWizardComponent implements OnInit {
  step = 1;
  stepLabels = ['Products', 'Strategy', 'Review'];
  products: { product: ThreeScaleProduct; selected: boolean }[] = [];
  productsLoading = true;
  gatewayStrategy = 'shared';
  analyzing = false;
  plan: MigrationPlan | null = null;
  yamlOpen: Record<number, boolean> = {};
  applying = false;
  applyResult: ApplyResult | null = null;

  strategies = [
    {
      value: 'shared',
      icon: '⎈',
      label: 'Shared gateway',
      description: 'One Gateway for all migrated workloads — fastest to operate and simplest Day-2 footprint.'
    },
    {
      value: 'dual',
      icon: '⇄',
      label: 'Dual gateway',
      description: 'Split internal and external traffic across two Gateways for stronger blast-radius control.'
    },
    {
      value: 'dedicated',
      icon: '◎',
      label: 'Dedicated per app',
      description: 'Isolate each application with its own Gateway when you need hard tenancy boundaries.'
    }
  ];

  get selectedCount(): number {
    return this.products.filter(p => p.selected).length;
  }

  constructor(private api: ApiService) {}

  ngOnInit(): void {
    this.api.getProducts().subscribe({
      next: (data) => {
        this.products = data.map(p => ({ product: p, selected: false }));
        this.productsLoading = false;
      },
      error: () => {
        this.productsLoading = false;
      }
    });
  }

  analyze(): void {
    this.analyzing = true;
    this.applyResult = null;
    const selected = this.products.filter(p => p.selected).map(p => p.product.name);
    this.api.analyzeMigration(this.gatewayStrategy, selected).subscribe({
      next: (plan) => {
        this.plan = plan;
        this.yamlOpen = {};
        this.step = 3;
        this.analyzing = false;
      },
      error: () => {
        this.analyzing = false;
      }
    });
  }

  applyMigration(): void {
    if (!this.plan) return;
    this.applying = true;
    this.api.applyPlan(this.plan.id).subscribe({
      next: (result) => {
        this.applyResult = result;
        this.applying = false;
      },
      error: () => {
        this.applyResult = { planId: this.plan!.id, applied: 0, failed: 1, results: [
          { kind: 'Error', name: 'Apply failed', namespace: '', success: false, message: 'Backend communication error' }
        ]};
        this.applying = false;
      }
    });
  }

  toggleYaml(idx: number): void {
    this.yamlOpen = { ...this.yamlOpen, [idx]: !this.yamlOpen[idx] };
  }
}

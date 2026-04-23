import { Component, CUSTOM_ELEMENTS_SCHEMA, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService, ThreeScaleProduct, MigrationPlan, ApplyResult, FeatureFlags, BulkRevertResult, TestCommand, TargetCluster } from '../../services/api.service';

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
          <span class="total-badge">{{ products.length }} total</span>
        </div>
        <p class="step-desc">Choose one or more 3scale products to include in the migration analysis.</p>

        <div *ngIf="products.length === 0" class="empty-inline card">
          <p>No products were found. Explore the cluster in <a routerLink="/threescale">3scale Explorer</a> or verify CRDs.</p>
        </div>

        <div *ngIf="products.length > 0">
          <div class="search-row">
            <input type="text" class="search-input" placeholder="Filter by name, namespace…" [(ngModel)]="productSearchQuery" (ngModelChange)="onProductSearchChange()">
            <button type="button" class="btn-select-filtered" (click)="selectAllFiltered()">
              {{ allFilteredSelected ? 'Deselect' : 'Select' }} filtered ({{ visibleProducts.length }})
            </button>
          </div>
          <div class="select-grid">
            <label *ngFor="let p of pagedMigrateProducts" class="select-card card" [class.checked]="p.selected">
              <input type="checkbox" class="visually-hidden" [(ngModel)]="p.selected" (click)="$event.stopPropagation()">
              <div class="check-corner" [class.on]="p.selected"></div>
              <div class="select-body">
                <div class="select-title-row">
                  <span class="product-name">{{ p.product.name }}</span>
                  <span class="badge badge-ns">{{ p.product.backendNamespace || p.product.namespace }}</span>
                </div>
                <div class="select-stats">
                  <span class="pill" *ngIf="p.product.backendServiceName">{{ p.product.backendServiceName }}</span>
                  <span class="pill pill-green">{{ p.product.backendUsages.length }} backends</span>
                </div>
              </div>
            </label>
          </div>
          <div class="page-controls-bar" *ngIf="migrateTotalPages > 1">
            <button class="page-btn" (click)="migrateProductPage = migrateProductPage - 1" [disabled]="migrateProductPage <= 1">&laquo; Prev</button>
            <span class="page-info">Page {{ migrateProductPage }} of {{ migrateTotalPages }}</span>
            <button class="page-btn" (click)="migrateProductPage = migrateProductPage + 1" [disabled]="migrateProductPage >= migrateTotalPages">Next &raquo;</button>
          </div>
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

        <div *ngIf="targetClusters.length > 1" class="cluster-selector">
          <h3>Target cluster</h3>
          <p class="step-desc">Choose which cluster receives the migrated resources.</p>
          <div class="cluster-grid">
            <label *ngFor="let c of targetClusters" class="cluster-card card" [class.selected]="selectedClusterId === c.id">
              <input type="radio" class="visually-hidden" name="targetCluster" [value]="c.id" [(ngModel)]="selectedClusterId">
              <div class="cluster-icon" aria-hidden="true">{{ c.id === 'local' ? '⎈' : '☁' }}</div>
              <div class="cluster-text">
                <span class="cluster-name">{{ c.label }}</span>
                <span class="cluster-url" *ngIf="c.apiServerUrl">{{ c.apiServerUrl }}</span>
              </div>
            </label>
          </div>
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
            <span class="pill pill-cluster" *ngIf="plan.targetClusterLabel">{{ plan.targetClusterLabel }}</span>
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

        <div *ngIf="developerHubEnabled && plan.catalogInfoYaml" class="catalog-info-section">
          <div class="catalog-info-header">
            <div style="display:flex; justify-content:space-between; align-items:center;">
              <h3>Developer Hub Integration</h3>
              <a *ngIf="developerHubUrl" [href]="developerHubUrl" target="_blank" class="btn-devhub-link">Open Developer Hub</a>
            </div>
            <p>Register this <code>catalog-info.yaml</code> in Developer Hub to see your migrated APIs in the catalog with Kuadrant plugin support.</p>
          </div>
          <div class="catalog-info-yaml">
            <div class="yaml-toolbar">
              <span class="badge badge-kind">catalog-info.yaml</span>
              <button type="button" class="btn-copy" (click)="copyCatalogInfo()">
                {{ catalogCopied ? 'Copied!' : 'Copy' }}
              </button>
            </div>
            <pre><code>{{ plan.catalogInfoYaml }}</code></pre>
          </div>
        </div>

        <div *ngIf="revertResult" class="apply-result" [class.success]="revertResult.failed === 0" [class.partial]="revertResult.failed > 0">
          <div class="apply-summary">
            <strong *ngIf="revertResult.failed === 0">Migration reverted successfully!</strong>
            <strong *ngIf="revertResult.failed > 0">Revert completed with errors</strong>
            <span class="apply-counts">
              {{ revertResult.applied }} deleted, {{ revertResult.failed }} failed
            </span>
          </div>
          <div class="apply-details">
            <div *ngFor="let r of revertResult.results" class="apply-row" [class.err]="!r.success">
              <span class="badge badge-kind">{{ r.kind }}</span>
              <span class="apply-name">{{ r.name }}</span>
              <span class="apply-status" [class.ok]="r.success" [class.fail]="!r.success">
                {{ r.success ? r.message : r.message }}
              </span>
            </div>
          </div>
        </div>

        <div *ngIf="testCommands.length > 0" class="test-section">
          <h3>Test your migration</h3>
          <p class="step-desc">Use these commands to verify your APIs work through Connectivity Link.</p>
          <div class="test-commands">
            <div *ngFor="let cmd of testCommands" class="test-cmd-row">
              <div class="test-cmd-label">
                <span class="pill" [class.pill-green]="cmd.type === 'api-key'" [class.pill-url]="cmd.type === 'url'">{{ cmd.type }}</span>
                <span>{{ cmd.label }}</span>
              </div>
              <div class="test-cmd-code">
                <code *ngIf="cmd.type !== 'url'">{{ cmd.command }}</code>
                <a *ngIf="cmd.type === 'url'" [href]="cmd.command" target="_blank">{{ cmd.command }}</a>
                <button type="button" class="btn-copy-sm" (click)="copyCommand(cmd.command)">Copy</button>
              </div>
            </div>
          </div>
        </div>

        <div class="actions">
          <button type="button" class="btn-secondary" (click)="step = 2">Back</button>
          <button type="button" class="btn-apply" (click)="applyMigration()" [disabled]="applying || !!applyResult || !!revertResult">
            {{ applying ? 'Applying…' : (applyResult ? 'Applied' : 'Apply to Cluster') }}
          </button>
          <button type="button" class="btn-revert" (click)="revertMigration()" [disabled]="reverting || !applyResult || !!revertResult"
                  *ngIf="applyResult">
            {{ reverting ? 'Reverting…' : (revertResult ? 'Reverted' : 'Revert Migration') }}
          </button>
        </div>
      </div>

      <div class="history-section">
        <div class="history-header" (click)="toggleHistory()">
          <h2>Migration History / Volver a 3scale</h2>
          <span class="chevron" [class.open]="historyOpen">⌄</span>
        </div>
        <div *ngIf="historyOpen" class="history-body">
          <div *ngIf="historyLoading" class="loading-text">Loading migration plans…</div>
          <div *ngIf="!historyLoading && allPlans.length === 0" class="empty-inline card">
            <p>No migration plans found. Create one using the wizard above.</p>
          </div>
          <div *ngIf="!historyLoading && allPlans.length > 0">
            <div class="history-toolbar">
              <label class="select-all-label">
                <input type="checkbox" [checked]="allSelected" (change)="toggleSelectAll()">
                Select All
              </label>
              <label class="delete-gw-label">
                <input type="checkbox" [(ngModel)]="deleteGateway">
                Also delete shared Gateway
              </label>
              <button type="button" class="btn-bulk-revert"
                      (click)="confirmBulkRevert()"
                      [disabled]="selectedPlanIds.length === 0 || bulkReverting">
                {{ bulkReverting ? 'Reverting…' : 'Volver a 3scale (' + selectedPlanIds.length + ')' }}
              </button>
            </div>
            <div class="history-list">
              <div *ngFor="let p of allPlans" class="history-row card" [class.reverted]="p.status === 'REVERTED'">
                <input type="checkbox" [checked]="planSelection[p.id]" (change)="togglePlanSelection(p.id)"
                       [disabled]="p.status === 'REVERTED'">
                <div class="history-info">
                  <span class="history-id">{{ p.id }}</span>
                  <span class="pill pill-strategy">{{ p.gatewayStrategy }}</span>
                  <span class="pill pill-muted">{{ p.sourceProducts.join(', ') }}</span>
                </div>
                <span class="badge" [class.badge-active]="p.status === 'ACTIVE'" [class.badge-reverted]="p.status === 'REVERTED'">
                  {{ p.status || 'ACTIVE' }}
                </span>
              </div>
            </div>
            <div *ngIf="bulkResult" class="apply-result" [class.success]="bulkResult.totalFailed === 0" [class.partial]="bulkResult.totalFailed > 0">
              <div class="apply-summary">
                <strong>Bulk revert: {{ bulkResult.totalReverted }} reverted, {{ bulkResult.totalFailed }} failed</strong>
              </div>
            </div>
          </div>
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
    .btn-revert {
      padding: 10px 22px; border-radius: 6px; font-weight: 600; cursor: pointer;
      border: none; font-family: inherit; font-size: 0.95rem;
      background: #c9190b; color: white;
    }
    .btn-revert:hover:not(:disabled) { background: #a30000; }
    .btn-revert:disabled { opacity: 0.5; cursor: not-allowed; }
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

    .catalog-info-section {
      margin-top: 32px;
      border: 2px solid #0066cc;
      border-radius: 12px;
      overflow: hidden;
    }
    .catalog-info-header {
      background: linear-gradient(135deg, #0066cc 0%, #004080 100%);
      padding: 20px 24px;
      color: white;
    }
    .catalog-info-header h3 {
      margin: 0 0 6px; font-size: 1.1rem; font-weight: 700;
    }
    .catalog-info-header p {
      margin: 0; font-size: 0.85rem; color: #cce0ff;
    }
    .catalog-info-header code {
      background: rgba(255,255,255,0.2); padding: 1px 6px; border-radius: 4px;
      font-size: 0.85rem;
    }
    .catalog-info-yaml {
      background: #1e1e1e; padding: 0;
    }
    .yaml-toolbar {
      display: flex; justify-content: space-between; align-items: center;
      padding: 10px 16px; border-bottom: 1px solid #333;
    }
    .yaml-toolbar .badge-kind {
      background: #0066cc; color: white;
    }
    .btn-copy {
      padding: 5px 14px; border-radius: 4px; font-size: 0.8rem;
      font-weight: 600; cursor: pointer;
      background: #3f9c35; color: white; border: none;
    }
    .btn-copy:hover { background: #2d6b24; }
    .catalog-info-yaml pre {
      margin: 0; padding: 16px; overflow-x: auto;
      font-size: 0.8rem; line-height: 1.5;
    }
    .catalog-info-yaml code {
      color: #d4d4d4; font-family: 'Red Hat Mono', monospace;
    }
    .btn-devhub-link {
      padding: 6px 16px; border-radius: 4px; font-size: 0.85rem; font-weight: 600;
      background: white; color: #0066cc; text-decoration: none;
      border: 1px solid rgba(255,255,255,0.3);
    }
    .btn-devhub-link:hover { background: #e0ecff; }

    .test-section {
      margin-top: 28px; padding: 20px 24px; background: #f0f7ff;
      border: 1px solid rgba(0,102,204,0.25); border-radius: 8px;
    }
    .test-section h3 { margin: 0 0 4px; font-size: 1.1rem; color: #151515; }
    .test-commands { display: flex; flex-direction: column; gap: 10px; }
    .test-cmd-row {
      background: white; border: 1px solid #d2d2d2; border-radius: 6px;
      padding: 12px 16px;
    }
    .test-cmd-label {
      display: flex; align-items: center; gap: 8px; margin-bottom: 6px;
      font-size: 0.88rem; font-weight: 500; color: #151515;
    }
    .test-cmd-code {
      display: flex; align-items: center; gap: 8px;
      background: #1e1e1e; padding: 8px 12px; border-radius: 4px;
    }
    .test-cmd-code code {
      flex: 1; color: #d4d4d4; font-size: 0.82rem; word-break: break-all;
      font-family: 'Red Hat Mono', monospace;
    }
    .test-cmd-code a {
      flex: 1; color: #4fc3f7; font-size: 0.82rem; word-break: break-all;
    }
    .pill-url { background: #e0ecff; border-color: #0066cc; color: #0066cc; }
    .btn-copy-sm {
      padding: 3px 10px; border-radius: 4px; font-size: 0.75rem; font-weight: 600;
      cursor: pointer; background: #3f9c35; color: white; border: none; white-space: nowrap;
    }
    .btn-copy-sm:hover { background: #2d6b24; }

    .history-section {
      margin-top: 40px; border: 1px solid #d2d2d2; border-radius: 8px; overflow: hidden;
    }
    .history-header {
      display: flex; align-items: center; justify-content: space-between;
      padding: 16px 20px; cursor: pointer; background: #fafafa;
    }
    .history-header:hover { background: #f0f0f0; }
    .history-header h2 { margin: 0; font-size: 1.15rem; }
    .history-body { padding: 16px 20px; }
    .history-toolbar {
      display: flex; align-items: center; gap: 16px; flex-wrap: wrap;
      margin-bottom: 14px; padding-bottom: 14px; border-bottom: 1px solid #e8e8e8;
    }
    .select-all-label, .delete-gw-label {
      display: flex; align-items: center; gap: 6px; font-size: 0.88rem; cursor: pointer;
    }
    .btn-bulk-revert {
      margin-left: auto; padding: 8px 20px; border-radius: 6px; font-weight: 600;
      cursor: pointer; border: none; font-family: inherit; font-size: 0.9rem;
      background: #c9190b; color: white;
    }
    .btn-bulk-revert:hover:not(:disabled) { background: #a30000; }
    .btn-bulk-revert:disabled { opacity: 0.5; cursor: not-allowed; }
    .history-list { display: flex; flex-direction: column; gap: 8px; }
    .history-row {
      display: flex; align-items: center; gap: 12px; padding: 12px 16px;
    }
    .history-row.reverted { opacity: 0.6; }
    .history-info { flex: 1; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    .history-id { font-weight: 600; font-family: 'Red Hat Mono', monospace; font-size: 0.88rem; }
    .badge-active { background: #e6f5e0; color: #2d6b24; }
    .badge-reverted { background: #f5f5f5; color: #6a6e73; }

    .total-badge {
      font-size: 0.78rem; color: #6a6e73; padding: 4px 12px;
      background: #f5f5f5; border-radius: 999px; border: 1px solid #d2d2d2;
    }
    .search-row {
      display: flex; align-items: center; gap: 12px; margin-bottom: 16px; flex-wrap: wrap;
    }
    .search-input {
      flex: 1; min-width: 240px; padding: 10px 16px; border: 1px solid #d2d2d2;
      border-radius: 6px; font-family: inherit; font-size: 0.92rem;
    }
    .search-input:focus { outline: none; border-color: #ee0000; box-shadow: 0 0 0 3px rgba(238,0,0,0.12); }
    .btn-select-filtered {
      padding: 8px 16px; border: 1px solid #d2d2d2; border-radius: 6px;
      background: white; cursor: pointer; font-family: inherit; font-size: 0.85rem;
      font-weight: 600; white-space: nowrap;
    }
    .btn-select-filtered:hover { border-color: #ee0000; color: #ee0000; }
    .page-controls-bar {
      display: flex; align-items: center; justify-content: center; gap: 10px;
      margin: 16px 0;
    }
    .page-btn {
      padding: 6px 14px; border: 1px solid #d2d2d2; border-radius: 4px;
      background: white; cursor: pointer; font-family: inherit; font-size: 0.82rem; font-weight: 600;
    }
    .page-btn:hover:not(:disabled) { border-color: #ee0000; color: #ee0000; }
    .page-btn:disabled { opacity: 0.4; cursor: not-allowed; }
    .page-info { font-size: 0.82rem; color: #6a6e73; }

    .cluster-selector { margin-top: 28px; }
    .cluster-selector h3 { font-family: 'Red Hat Display', sans-serif; font-size: 1.1rem; margin: 0 0 4px; }
    .cluster-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 14px; margin-bottom: 24px; }
    .cluster-card { display: flex; gap: 12px; padding: 16px 18px; cursor: pointer; align-items: center; }
    .cluster-card.selected { border-color: #0066cc; box-shadow: 0 4px 18px rgba(0,102,204,0.12); }
    .cluster-icon { width: 40px; height: 40px; border-radius: 10px; background: #f5f5f5; border: 1px solid #d2d2d2; display: flex; align-items: center; justify-content: center; font-size: 1.2rem; flex-shrink: 0; }
    .cluster-name { display: block; font-weight: 600; color: #151515; font-size: 0.95rem; }
    .cluster-url { display: block; font-size: 0.78rem; color: #6a6e73; word-break: break-all; }
    .pill-cluster { background: #e8eaf6; border-color: #3f51b5; color: #283593; font-weight: 600; }
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
  reverting = false;
  revertResult: ApplyResult | null = null;
  catalogCopied = false;
  developerHubEnabled = false;
  developerHubUrl = '';
  testCommands: TestCommand[] = [];
  historyOpen = false;
  historyLoading = false;
  allPlans: MigrationPlan[] = [];
  planSelection: Record<string, boolean> = {};
  deleteGateway = false;
  bulkReverting = false;
  bulkResult: BulkRevertResult | null = null;
  productSearchQuery = '';
  migrateProductPage = 1;
  migratePageSize = 24;
  targetClusters: TargetCluster[] = [];
  selectedClusterId = 'local';

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

  get visibleProducts(): { product: ThreeScaleProduct; selected: boolean }[] {
    if (!this.productSearchQuery) return this.products;
    const q = this.productSearchQuery.toLowerCase();
    return this.products.filter(p =>
      p.product.name.toLowerCase().includes(q) ||
      (p.product.namespace || '').toLowerCase().includes(q) ||
      (p.product.backendNamespace || '').toLowerCase().includes(q) ||
      (p.product.systemName || '').toLowerCase().includes(q)
    );
  }

  get migrateTotalPages(): number { return Math.max(1, Math.ceil(this.visibleProducts.length / this.migratePageSize)); }

  get pagedMigrateProducts(): { product: ThreeScaleProduct; selected: boolean }[] {
    const start = (this.migrateProductPage - 1) * this.migratePageSize;
    return this.visibleProducts.slice(start, start + this.migratePageSize);
  }

  get allFilteredSelected(): boolean {
    const vis = this.visibleProducts;
    return vis.length > 0 && vis.every(p => p.selected);
  }

  onProductSearchChange(): void { this.migrateProductPage = 1; }

  selectAllFiltered(): void {
    const target = !this.allFilteredSelected;
    this.visibleProducts.forEach(p => p.selected = target);
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
    this.api.getFeatures().subscribe({
      next: (f) => {
        this.developerHubEnabled = f.developerHub?.enabled ?? false;
        this.developerHubUrl = f.developerHub?.url ?? '';
      }
    });
    this.api.getTargetClusters().subscribe({
      next: (clusters) => this.targetClusters = clusters,
      error: () => this.targetClusters = [{ id: 'local', label: 'Local (in-cluster)', apiServerUrl: '', token: '', authType: 'in-cluster', verifySsl: true, enabled: true }]
    });
  }

  analyze(): void {
    this.analyzing = true;
    this.applyResult = null;
    this.revertResult = null;
    const selected = this.products.filter(p => p.selected).map(p => p.product.name);
    this.api.analyzeMigration(this.gatewayStrategy, selected, this.selectedClusterId).subscribe({
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
        this.loadTestCommands();
      },
      error: () => {
        this.applyResult = { planId: this.plan!.id, applied: 0, failed: 1, results: [
          { kind: 'Error', name: 'Apply failed', namespace: '', success: false, message: 'Backend communication error' }
        ]};
        this.applying = false;
      }
    });
  }

  revertMigration(): void {
    if (!this.plan) return;
    this.reverting = true;
    this.api.revertPlan(this.plan.id).subscribe({
      next: (result) => {
        this.revertResult = result;
        this.reverting = false;
      },
      error: () => {
        this.revertResult = { planId: this.plan!.id, applied: 0, failed: 1, results: [
          { kind: 'Error', name: 'Revert failed', namespace: '', success: false, message: 'Backend communication error' }
        ]};
        this.reverting = false;
      }
    });
  }

  toggleYaml(idx: number): void {
    this.yamlOpen = { ...this.yamlOpen, [idx]: !this.yamlOpen[idx] };
  }

  copyCatalogInfo(): void {
    if (!this.plan?.catalogInfoYaml) return;
    navigator.clipboard.writeText(this.plan.catalogInfoYaml).then(() => {
      this.catalogCopied = true;
      setTimeout(() => this.catalogCopied = false, 2000);
    });
  }

  copyCommand(cmd: string): void {
    navigator.clipboard.writeText(cmd);
  }

  loadTestCommands(): void {
    if (!this.plan) return;
    this.api.getTestCommands(this.plan.id).subscribe({
      next: (cmds) => this.testCommands = cmds,
      error: () => this.testCommands = []
    });
  }

  toggleHistory(): void {
    this.historyOpen = !this.historyOpen;
    if (this.historyOpen && this.allPlans.length === 0) {
      this.historyLoading = true;
      this.api.getPlans().subscribe({
        next: (plans) => {
          this.allPlans = plans;
          this.historyLoading = false;
        },
        error: () => this.historyLoading = false
      });
    }
  }

  get selectedPlanIds(): string[] {
    return Object.entries(this.planSelection)
      .filter(([, v]) => v)
      .map(([k]) => k);
  }

  get allSelected(): boolean {
    const active = this.allPlans.filter(p => p.status !== 'REVERTED');
    return active.length > 0 && active.every(p => this.planSelection[p.id]);
  }

  toggleSelectAll(): void {
    const allSel = this.allSelected;
    this.allPlans.filter(p => p.status !== 'REVERTED').forEach(p => {
      this.planSelection[p.id] = !allSel;
    });
  }

  togglePlanSelection(id: string): void {
    this.planSelection = { ...this.planSelection, [id]: !this.planSelection[id] };
  }

  confirmBulkRevert(): void {
    const count = this.selectedPlanIds.length;
    if (!confirm(`This will delete Connectivity Link resources for ${count} plan(s). 3scale will resume routing. Continue?`)) return;
    this.bulkReverting = true;
    this.bulkResult = null;
    this.api.revertBulk(this.selectedPlanIds, this.deleteGateway).subscribe({
      next: (result) => {
        this.bulkResult = result;
        this.bulkReverting = false;
        this.allPlans = this.allPlans.map(p =>
          this.selectedPlanIds.includes(p.id) ? { ...p, status: 'REVERTED' } : p
        );
        this.planSelection = {};
      },
      error: () => {
        this.bulkReverting = false;
      }
    });
  }
}

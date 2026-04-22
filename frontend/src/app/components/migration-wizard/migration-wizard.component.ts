import { Component, CUSTOM_ELEMENTS_SCHEMA, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService, ThreeScaleProduct, MigrationPlan } from '../../services/api.service';

@Component({
  selector: 'app-migration-wizard',
  standalone: true,
  imports: [CommonModule, FormsModule],
  schemas: [CUSTOM_ELEMENTS_SCHEMA],
  template: `
    <div class="container main-content">
      <h2>Migration Wizard</h2>
      <p>Plan and execute your 3scale to Connectivity Link migration</p>

      <!-- Step 1: Select products -->
      <rh-card *ngIf="step === 1">
        <h3 slot="header">Step 1: Select 3scale Products</h3>
        <div *ngIf="products.length === 0">
          <p>No products found. Load from cluster first.</p>
        </div>
        <div *ngFor="let p of products" class="product-check">
          <label>
            <input type="checkbox" [(ngModel)]="p.selected">
            {{ p.product.name }} ({{ p.product.namespace }})
            - {{ p.product.mappingRules.length }} rules, {{ p.product.backendUsages.length }} backends
          </label>
        </div>
        <div class="actions" slot="footer">
          <button (click)="step = 2" [disabled]="selectedCount === 0">Next: Gateway Strategy</button>
        </div>
      </rh-card>

      <!-- Step 2: Choose strategy -->
      <rh-card *ngIf="step === 2">
        <h3 slot="header">Step 2: Gateway Strategy</h3>
        <div class="strategy-options">
          <label class="strategy-option" *ngFor="let s of strategies">
            <input type="radio" name="strategy" [value]="s.value" [(ngModel)]="gatewayStrategy">
            <div class="strategy-card">
              <strong>{{ s.label }}</strong>
              <p>{{ s.description }}</p>
            </div>
          </label>
        </div>
        <div class="actions" slot="footer">
          <button (click)="step = 1">Back</button>
          <button (click)="analyze()" [disabled]="analyzing">
            {{ analyzing ? 'Analyzing...' : 'Analyze Migration' }}
          </button>
        </div>
      </rh-card>

      <!-- Step 3: Review plan -->
      <rh-card *ngIf="step === 3 && plan">
        <h3 slot="header">Step 3: Review Migration Plan</h3>
        <rh-alert state="info">
          <h4 slot="header">Plan {{ plan.id }}</h4>
          <p>Strategy: {{ plan.gatewayStrategy }} | Products: {{ plan.sourceProducts.join(', ') }}</p>
        </rh-alert>
        <div *ngFor="let res of plan.resources" class="resource-preview">
          <h4>
            <rh-badge>{{ res.kind }}</rh-badge>
            {{ res.name }} ({{ res.namespace }})
          </h4>
          <pre><code>{{ res.yaml }}</code></pre>
        </div>
        <div class="actions" slot="footer">
          <button (click)="step = 2">Back</button>
        </div>
      </rh-card>
    </div>
  `,
  styles: [`
    h2 { margin-bottom: 8px; font-family: 'Red Hat Display', sans-serif; }
    .product-check { padding: 8px 0; }
    .product-check label { display: flex; align-items: center; gap: 8px; cursor: pointer; }
    .strategy-options { display: flex; flex-direction: column; gap: 12px; margin: 16px 0; }
    .strategy-option { display: flex; align-items: flex-start; gap: 12px; cursor: pointer; }
    .strategy-card { padding: 12px; border: 1px solid #d2d2d2; border-radius: 8px; flex: 1; }
    .strategy-card p { color: #6a6e73; font-size: 0.9em; margin-top: 4px; }
    .actions { display: flex; gap: 12px; margin-top: 16px; }
    button {
      padding: 8px 20px; border: none; border-radius: 4px; cursor: pointer;
      background: #ee0000; color: white; font-weight: 600;
      &:disabled { opacity: 0.5; cursor: not-allowed; }
      &:hover:not(:disabled) { background: #c00; }
    }
    .resource-preview { margin: 16px 0; }
    pre { background: #1e1e1e; color: #d4d4d4; padding: 16px; border-radius: 6px;
          overflow-x: auto; font-size: 0.85em; margin-top: 8px; }
  `]
})
export class MigrationWizardComponent implements OnInit {
  step = 1;
  products: { product: ThreeScaleProduct; selected: boolean }[] = [];
  gatewayStrategy = 'shared';
  analyzing = false;
  plan: MigrationPlan | null = null;

  strategies = [
    { value: 'shared', label: 'Single Shared Gateway',
      description: 'One Gateway for all migrated applications. Simplest setup.' },
    { value: 'dual', label: 'Dual Gateway (Internal + External)',
      description: 'Two Gateways: one for internal services, one for external-facing APIs.' },
    { value: 'dedicated', label: 'Dedicated Gateway per Application',
      description: 'Each application gets its own Gateway. Maximum isolation.' }
  ];

  get selectedCount() {
    return this.products.filter(p => p.selected).length;
  }

  constructor(private api: ApiService) {}

  ngOnInit() {
    this.api.getProducts().subscribe({
      next: (data) => { this.products = data.map(p => ({ product: p, selected: false })); },
      error: () => {}
    });
  }

  analyze() {
    this.analyzing = true;
    const selected = this.products.filter(p => p.selected).map(p => p.product.name);
    this.api.analyzeMigration(this.gatewayStrategy, selected).subscribe({
      next: (plan) => { this.plan = plan; this.step = 3; this.analyzing = false; },
      error: () => { this.analyzing = false; }
    });
  }
}

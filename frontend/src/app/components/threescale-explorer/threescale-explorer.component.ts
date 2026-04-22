import { Component, CUSTOM_ELEMENTS_SCHEMA, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService, ThreeScaleProduct } from '../../services/api.service';

@Component({
  selector: 'app-threescale-explorer',
  standalone: true,
  imports: [CommonModule],
  schemas: [CUSTOM_ELEMENTS_SCHEMA],
  template: `
    <div class="container main-content">
      <h2>3scale Explorer</h2>
      <p>Products and Backends discovered from 3scale CRDs in the cluster</p>

      <rh-spinner *ngIf="loading"></rh-spinner>

      <div *ngIf="!loading && products.length === 0" class="empty-state">
        <rh-card>
          <h3 slot="header">No 3scale Products Found</h3>
          <p>No 3scale Product CRDs were found in the cluster.
             Ensure 3scale operator is installed and products are configured.</p>
        </rh-card>
      </div>

      <rh-accordion *ngIf="!loading && products.length > 0">
        <div *ngFor="let product of products">
          <rh-accordion-header>
            <h3>{{ product.name }}
              <rh-badge>{{ product.namespace }}</rh-badge>
            </h3>
          </rh-accordion-header>
          <rh-accordion-panel>
            <div class="product-details">
              <p><strong>System Name:</strong> {{ product.systemName }}</p>
              <p><strong>Description:</strong> {{ product.description || 'N/A' }}</p>
              <p><strong>Deployment:</strong> {{ product.deploymentOption || 'N/A' }}</p>

              <h4>Mapping Rules ({{ product.mappingRules.length }})</h4>
              <table *ngIf="product.mappingRules.length > 0">
                <thead>
                  <tr><th>Method</th><th>Pattern</th><th>Metric</th><th>Delta</th></tr>
                </thead>
                <tbody>
                  <tr *ngFor="let rule of product.mappingRules">
                    <td>{{ rule.httpMethod }}</td>
                    <td><code>{{ rule.pattern }}</code></td>
                    <td>{{ rule.metricRef }}</td>
                    <td>{{ rule.delta }}</td>
                  </tr>
                </tbody>
              </table>

              <h4>Backend Usages ({{ product.backendUsages.length }})</h4>
              <ul>
                <li *ngFor="let bu of product.backendUsages">
                  {{ bu.backendName }} &rarr; {{ bu.path }}
                </li>
              </ul>
            </div>
          </rh-accordion-panel>
        </div>
      </rh-accordion>
    </div>
  `,
  styles: [`
    h2 { margin-bottom: 8px; font-family: 'Red Hat Display', sans-serif; }
    .product-details { padding: 16px 0; }
    h4 { margin: 16px 0 8px; }
    table { width: 100%; border-collapse: collapse; margin-bottom: 16px; }
    th, td { padding: 8px 12px; text-align: left; border-bottom: 1px solid #d2d2d2; }
    th { background: #f0f0f0; font-weight: 600; }
    code { background: #e0e0e0; padding: 2px 6px; border-radius: 3px; font-size: 0.9em; }
    .empty-state { margin-top: 24px; }
    ul { padding-left: 24px; }
  `]
})
export class ThreeScaleExplorerComponent implements OnInit {
  products: ThreeScaleProduct[] = [];
  loading = true;

  constructor(private api: ApiService) {}

  ngOnInit() {
    this.api.getProducts().subscribe({
      next: (data) => { this.products = data; this.loading = false; },
      error: () => { this.loading = false; }
    });
  }
}

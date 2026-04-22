import { Component, CUSTOM_ELEMENTS_SCHEMA, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService, AuditEntry } from '../../services/api.service';

@Component({
  selector: 'app-audit-log',
  standalone: true,
  imports: [CommonModule],
  schemas: [CUSTOM_ELEMENTS_SCHEMA],
  template: `
    <div class="container main-content">
      <h2>Audit Log</h2>
      <p>Track all migration actions and changes</p>

      <rh-spinner *ngIf="loading"></rh-spinner>

      <div *ngIf="!loading && entries.length === 0" class="empty-state">
        <rh-card>
          <h3 slot="header">No audit entries yet</h3>
          <p>Run a migration to see the audit trail here.</p>
        </rh-card>
      </div>

      <div *ngIf="!loading" class="audit-list">
        <rh-card *ngFor="let entry of entries">
          <h3 slot="header">
            <rh-badge>{{ entry.action }}</rh-badge>
            {{ entry.resourceKind }} / {{ entry.resourceName }}
          </h3>
          <p><strong>Namespace:</strong> {{ entry.namespace }}</p>
          <p><strong>Time:</strong> {{ entry.timestamp }}</p>
          <p><strong>By:</strong> {{ entry.performedBy }}</p>
          <div *ngIf="entry.yamlAfter" class="diff">
            <h4>Generated YAML:</h4>
            <pre><code>{{ entry.yamlAfter }}</code></pre>
          </div>
        </rh-card>
      </div>
    </div>
  `,
  styles: [`
    h2 { margin-bottom: 8px; font-family: 'Red Hat Display', sans-serif; }
    .audit-list { display: flex; flex-direction: column; gap: 16px; margin-top: 16px; }
    pre { background: #1e1e1e; color: #d4d4d4; padding: 12px; border-radius: 6px;
          overflow-x: auto; font-size: 0.85em; margin-top: 8px; }
    .empty-state { margin-top: 24px; }
    rh-badge { margin-right: 8px; }
  `]
})
export class AuditLogComponent implements OnInit {
  entries: AuditEntry[] = [];
  loading = true;

  constructor(private api: ApiService) {}

  ngOnInit() {
    this.api.getAuditLog().subscribe({
      next: (data) => { this.entries = data; this.loading = false; },
      error: () => { this.loading = false; }
    });
  }
}

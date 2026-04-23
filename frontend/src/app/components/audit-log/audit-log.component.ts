import { Component, CUSTOM_ELEMENTS_SCHEMA, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ApiService, AuditEntry } from '../../services/api.service';

@Component({
  selector: 'app-audit-log',
  standalone: true,
  imports: [CommonModule],
  schemas: [CUSTOM_ELEMENTS_SCHEMA],
  template: `
    <header class="page-header">
      <div class="container header-inner">
        <div>
          <h1>Audit log</h1>
          <p class="subtitle">Immutable trail of migration actions, resource changes, and applied YAML.</p>
        </div>
        <div class="count-pill" *ngIf="!loading">
          <span class="count-num">{{ entries.length }}</span>
          <span class="count-label">entries</span>
        </div>
      </div>
    </header>

    <section class="container main-content">
      <div *ngIf="loading" class="loading-state">
        <div class="skeleton-bar"></div>
        <div class="timeline skeleton-timeline">
          <div *ngFor="let i of [1,2,3,4]" class="timeline-item">
            <div class="skeleton-card">
              <div class="skeleton-line long"></div>
              <div class="skeleton-line short"></div>
            </div>
          </div>
        </div>
        <p class="loading-text">Loading audit entries…</p>
      </div>

      <div *ngIf="!loading && entries.length === 0" class="empty-state card">
        <div class="empty-art" aria-hidden="true">
          <svg viewBox="0 0 64 64" width="56" height="56" fill="none" xmlns="http://www.w3.org/2000/svg">
            <rect x="8" y="10" width="48" height="40" rx="6" stroke="#d2d2d2" stroke-width="2"/>
            <path d="M16 22h32M16 30h24M16 38h18" stroke="#d2d2d2" stroke-width="2" stroke-linecap="round"/>
            <circle cx="48" cy="44" r="10" fill="#f5f5f5" stroke="#d2d2d2" stroke-width="2"/>
            <path d="M44 44l2.5 2.5L52 39" stroke="#3f9c35" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </div>
        <p class="empty-lead">No audit entries yet. Run a migration to see the audit trail.</p>
      </div>

      <div *ngIf="!loading && entries.length > 0" class="timeline" role="list">
        <div *ngFor="let entry of entries; let last = last" class="timeline-item" role="listitem">
          <div class="timeline-rail">
            <span class="timeline-dot" [ngClass]="actionTone(entry.action)"></span>
            <div *ngIf="!last" class="timeline-line"></div>
          </div>
          <article class="entry-card card">
            <div class="entry-top">
              <span class="action-badge" [ngClass]="actionTone(entry.action)">{{ entry.action }}</span>
              <time class="timestamp">{{ entry.timestamp }}</time>
            </div>
            <h2 class="resource-line">
              <span class="kind">{{ entry.resourceKind }}</span>
              <span class="sep">/</span>
              <span class="name">{{ entry.resourceName }}</span>
            </h2>
            <div class="meta-row">
              <span class="meta-pill">Namespace: {{ entry.namespace }}</span>
              <span class="meta-pill">By: {{ entry.performedBy }}</span>
              <span class="meta-pill meta-cluster" *ngIf="entry.targetClusterId && entry.targetClusterId !== 'local'">Cluster: {{ entry.targetClusterId }}</span>
            </div>
            <button
              type="button"
              class="yaml-toggle"
              (click)="toggleYaml(entry.id)"
              [attr.aria-expanded]="expanded[entry.id]">
              {{ expanded[entry.id] ? 'Hide YAML' : 'Show YAML' }}
              <span class="chev" [class.open]="expanded[entry.id]">⌄</span>
            </button>
            <div *ngIf="expanded[entry.id]" class="yaml-blocks">
              <div *ngIf="entry.yamlBefore" class="yaml-block">
                <span class="yaml-label">Before</span>
                <pre><code>{{ entry.yamlBefore }}</code></pre>
              </div>
              <div *ngIf="entry.yamlAfter" class="yaml-block">
                <span class="yaml-label">After</span>
                <pre><code>{{ entry.yamlAfter }}</code></pre>
              </div>
              <p *ngIf="!entry.yamlBefore && !entry.yamlAfter" class="muted">No YAML payload recorded for this entry.</p>
            </div>
          </article>
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
      max-width: 720px;
      line-height: 1.5;
    }
    .container {
      max-width: 1280px;
      margin: 0 auto;
      padding: 0 24px;
    }
    .header-inner {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 16px;
      flex-wrap: wrap;
    }
    .count-pill {
      background: rgba(255,255,255,0.1);
      border: 1px solid rgba(255,255,255,0.2);
      border-radius: 8px;
      padding: 10px 18px;
      text-align: center;
    }
    .count-num {
      display: block;
      font-family: 'Red Hat Display', sans-serif;
      font-size: 1.4rem;
      font-weight: 700;
    }
    .count-label { font-size: 0.78rem; color: #c9c9c9; }
    .main-content { padding: 32px 0 48px; }
    .card {
      background: white;
      border: 1px solid #d2d2d2;
      border-radius: 8px;
      transition: box-shadow 0.2s;
    }
    .card:hover { box-shadow: 0 4px 16px rgba(0,0,0,0.07); }
    .timeline {
      position: relative;
      display: flex;
      flex-direction: column;
      gap: 0;
    }
    .timeline-item {
      display: grid;
      grid-template-columns: 28px 1fr;
      gap: 16px 20px;
      align-items: start;
    }
    .timeline-rail {
      display: flex;
      flex-direction: column;
      align-items: center;
      min-height: 100%;
    }
    .timeline-dot {
      width: 14px;
      height: 14px;
      border-radius: 50%;
      border: 3px solid white;
      box-shadow: 0 0 0 2px #d2d2d2;
      margin-top: 22px;
      flex-shrink: 0;
      background: #f5f5f5;
    }
    .timeline-dot.tone-create { background: #3f9c35; box-shadow: 0 0 0 2px rgba(63,156,53,0.35); }
    .timeline-dot.tone-update { background: #0066cc; box-shadow: 0 0 0 2px rgba(0,102,204,0.35); }
    .timeline-dot.tone-delete { background: #ee0000; box-shadow: 0 0 0 2px rgba(238,0,0,0.35); }
    .timeline-dot.tone-neutral { background: #6a6e73; box-shadow: 0 0 0 2px rgba(106,110,115,0.35); }
    .timeline-line {
      width: 2px;
      flex: 1;
      min-height: 32px;
      background: #d2d2d2;
      margin: 4px 0 0;
    }
    .entry-card {
      padding: 18px 20px 16px;
      margin-bottom: 20px;
    }
    .entry-top {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      flex-wrap: wrap;
      margin-bottom: 10px;
    }
    .action-badge {
      display: inline-block;
      padding: 4px 12px;
      border-radius: 999px;
      font-size: 0.72rem;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.03em;
    }
    .action-badge.tone-create { background: #e6f5e0; color: #2d6b24; }
    .action-badge.tone-update { background: #e6f0ff; color: #004080; }
    .action-badge.tone-delete { background: #ffecec; color: #a30000; }
    .action-badge.tone-neutral { background: #f5f5f5; color: #6a6e73; border: 1px solid #d2d2d2; }
    .timestamp { font-size: 0.82rem; color: #6a6e73; }
    .resource-line {
      font-family: 'Red Hat Display', sans-serif;
      font-size: 1.05rem;
      margin: 0 0 10px;
      color: #151515;
      font-weight: 600;
      word-break: break-word;
    }
    .kind { color: #0066cc; }
    .sep { color: #6a6e73; margin: 0 4px; }
    .name { color: #151515; }
    .meta-row { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }
    .meta-pill {
      font-size: 0.78rem;
      padding: 4px 10px;
      border-radius: 999px;
      background: #f5f5f5;
      border: 1px solid #d2d2d2;
      color: #6a6e73;
    }
    .yaml-toggle {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      background: none;
      border: none;
      padding: 0;
      color: #ee0000;
      font-weight: 600;
      cursor: pointer;
      font-family: inherit;
      font-size: 0.88rem;
    }
    .yaml-toggle:hover { text-decoration: underline; }
    .chev {
      display: inline-block;
      transition: transform 0.2s;
      font-size: 1rem;
      line-height: 1;
    }
    .chev.open { transform: rotate(180deg); }
    .yaml-blocks { margin-top: 14px; }
    .yaml-block + .yaml-block { margin-top: 12px; }
    .yaml-label {
      display: block;
      font-size: 0.72rem;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      color: #6a6e73;
      margin-bottom: 6px;
    }
    .yaml-blocks pre {
      margin: 0;
      background: #1e1e1e;
      color: #d4d4d4;
      padding: 14px;
      border-radius: 6px;
      overflow-x: auto;
      font-size: 0.82rem;
    }
    .muted { color: #6a6e73; font-size: 0.88rem; margin: 0; }
    .meta-cluster { background: #e8eaf6; border-color: #3f51b5; color: #283593; }
    .empty-state {
      text-align: center;
      padding: 48px 28px;
      max-width: 520px;
      margin: 0 auto;
    }
    .empty-lead {
      font-family: 'Red Hat Display', sans-serif;
      font-size: 1.05rem;
      font-weight: 600;
      color: #151515;
      line-height: 1.55;
      margin: 16px 0 0;
    }
    .empty-art { display: flex; justify-content: center; }
    @keyframes shimmer {
      0% { background-position: -400px 0; }
      100% { background-position: 400px 0; }
    }
    .loading-state { padding: 8px 0 24px; }
    .loading-text { text-align: center; color: #6a6e73; margin-top: 20px; }
    .skeleton-bar {
      height: 36px; border-radius: 6px; margin-bottom: 20px;
      background: linear-gradient(90deg, #e8e8e8 25%, #f5f5f5 50%, #e8e8e8 75%);
      background-size: 800px 100%; animation: shimmer 1.5s infinite;
    }
    .skeleton-timeline .skeleton-card {
      padding: 18px 20px;
      margin-bottom: 16px;
      border-radius: 8px;
      background: white;
      border: 1px solid #e8e8e8;
    }
    .skeleton-line {
      height: 14px; border-radius: 4px; margin-bottom: 10px;
      background: linear-gradient(90deg, #e8e8e8 25%, #f5f5f5 50%, #e8e8e8 75%);
      background-size: 800px 100%; animation: shimmer 1.5s infinite;
    }
    .skeleton-line.long { width: 70%; }
    .skeleton-line.short { width: 40%; }
    @media (max-width: 600px) {
      .timeline-item { grid-template-columns: 20px 1fr; gap: 12px; }
    }
  `]
})
export class AuditLogComponent implements OnInit {
  entries: AuditEntry[] = [];
  loading = true;
  expanded: Record<string, boolean> = {};

  constructor(private api: ApiService) {}

  ngOnInit(): void {
    this.api.getAuditLog().subscribe({
      next: (data) => {
        this.entries = data;
        this.loading = false;
      },
      error: () => {
        this.loading = false;
      }
    });
  }

  toggleYaml(id: string): void {
    this.expanded = { ...this.expanded, [id]: !this.expanded[id] };
  }

  actionTone(action: string): string {
    const a = (action || '').toLowerCase();
    if (a.includes('create') || a.includes('add')) return 'tone-create';
    if (a.includes('update') || a.includes('patch') || a.includes('apply')) return 'tone-update';
    if (a.includes('delete') || a.includes('remove')) return 'tone-delete';
    return 'tone-neutral';
  }
}

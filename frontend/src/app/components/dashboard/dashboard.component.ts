import { Component, CUSTOM_ELEMENTS_SCHEMA, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService, ProjectInfo } from '../../services/api.service';

const SYSTEM_PREFIXES = [
  'openshift-', 'kube-', 'default', 'openshift'
];

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  schemas: [CUSTOM_ELEMENTS_SCHEMA],
  template: `
    <section class="hero-banner">
      <div class="container">
        <div class="hero-content">
          <div class="hero-text">
            <h1>Welcome to GateForge</h1>
            <p>AI-powered migration from <strong>Red Hat 3scale</strong> to <strong>Connectivity Link (Kuadrant)</strong> on OpenShift</p>
            <div class="hero-actions">
              <a routerLink="/chat" class="btn btn-primary">Start AI Chat</a>
              <a routerLink="/migrate" class="btn btn-outline">Run Migration</a>
            </div>
          </div>
          <div class="hero-stats" *ngIf="!loading">
            <div class="stat-card">
              <span class="stat-number">{{ userProjects.length }}</span>
              <span class="stat-label">User Projects</span>
            </div>
            <div class="stat-card highlight">
              <span class="stat-number">{{ threeScaleCount }}</span>
              <span class="stat-label">With 3scale</span>
            </div>
            <div class="stat-card success">
              <span class="stat-number">{{ kuadrantCount }}</span>
              <span class="stat-label">With Kuadrant</span>
            </div>
            <div class="stat-card muted">
              <span class="stat-number">{{ systemProjects.length }}</span>
              <span class="stat-label">System (hidden)</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <section class="container main-content">

      <div class="getting-started" *ngIf="!loading">
        <h2>Getting Started</h2>
        <div class="steps-grid">
          <a routerLink="/threescale" class="step-card">
            <div class="step-num">1</div>
            <div>
              <h4>Explore 3scale</h4>
              <p>Discover Products, Backends and Mapping Rules from 3scale CRDs in your cluster.</p>
            </div>
          </a>
          <a routerLink="/chat" class="step-card">
            <div class="step-num">2</div>
            <div>
              <h4>Ask the AI</h4>
              <p>Use the AI assistant to analyze configurations and get migration recommendations.</p>
            </div>
          </a>
          <a routerLink="/migrate" class="step-card">
            <div class="step-num">3</div>
            <div>
              <h4>Plan Migration</h4>
              <p>Select products, choose a gateway strategy, and generate Kuadrant resources.</p>
            </div>
          </a>
          <a routerLink="/audit" class="step-card">
            <div class="step-num">4</div>
            <div>
              <h4>Review Changes</h4>
              <p>Track every action in the audit log with full YAML diffs.</p>
            </div>
          </a>
        </div>
      </div>

      <div class="prompt-section" *ngIf="!loading">
        <h2>Try These AI Prompts</h2>
        <p class="section-desc">Click any prompt to open the AI Chat with it pre-filled.</p>
        <div class="prompt-chips">
          <a *ngFor="let p of samplePrompts" routerLink="/chat" [queryParams]="{q: p}" class="prompt-chip">{{ p }}</a>
        </div>
      </div>

      <div class="projects-section" *ngIf="!loading">
        <div class="section-header">
          <h2>Cluster Projects</h2>
          <div class="filter-bar">
            <input type="text" [(ngModel)]="searchTerm" placeholder="Filter projects..." class="search-input">
            <label class="toggle-label">
              <input type="checkbox" [(ngModel)]="showSystem" (change)="applyFilter()">
              Show system projects ({{ systemProjects.length }})
            </label>
          </div>
        </div>

        <div class="project-grid">
          <div *ngFor="let project of filteredProjects" class="project-card"
               [class.has-threescale]="project.hasThreeScale"
               [class.has-kuadrant]="project.hasKuadrant">
            <div class="project-header">
              <h4>{{ project.name }}</h4>
              <div class="badges">
                <span *ngIf="project.hasThreeScale" class="badge badge-3scale">3scale</span>
                <span *ngIf="project.hasKuadrant" class="badge badge-kuadrant">Kuadrant</span>
              </div>
            </div>
            <div class="project-meta">
              <span class="status" [class.active]="project.status === 'Active'">{{ project.status }}</span>
              <span class="date">{{ project.creationTimestamp | date:'mediumDate' }}</span>
            </div>
          </div>
        </div>

        <div *ngIf="filteredProjects.length === 0" class="empty-state">
          <p *ngIf="loading">Loading cluster projects...</p>
          <p *ngIf="!loading && allProjects.length === 0">No projects found. Ensure the backend has cluster-admin access.</p>
          <p *ngIf="!loading && allProjects.length > 0">No projects match your filter.</p>
        </div>
      </div>

      <div *ngIf="loading" class="loading-state">
        <rh-spinner></rh-spinner>
        <p>Loading cluster data...</p>
      </div>

      <div class="links-section" *ngIf="!loading">
        <h2>Documentation &amp; Resources</h2>
        <div class="links-grid">
          <a href="https://docs.redhat.com/en/documentation/red_hat_connectivity_link" target="_blank" class="link-card">
            <strong>Connectivity Link</strong>
            <p>Official Red Hat product documentation</p>
          </a>
          <a href="https://docs.kuadrant.io/" target="_blank" class="link-card">
            <strong>Kuadrant Docs</strong>
            <p>AuthPolicy, RateLimitPolicy, DNSPolicy, TLSPolicy</p>
          </a>
          <a href="https://github.com/Kuadrant/kuadrantctl" target="_blank" class="link-card">
            <strong>kuadrantctl CLI</strong>
            <p>Generate Gateway API resources from OpenAPI specs</p>
          </a>
          <a href="https://docs.redhat.com/en/documentation/red_hat_3scale_api_management" target="_blank" class="link-card">
            <strong>3scale Docs</strong>
            <p>Admin API reference and operator CRDs</p>
          </a>
          <a href="https://gateway-api.sigs.k8s.io/" target="_blank" class="link-card">
            <strong>Gateway API</strong>
            <p>HTTPRoute, Gateway, GatewayClass specification</p>
          </a>
          <a href="https://maximilianopizarro.github.io/gateforge/" target="_blank" class="link-card">
            <strong>GateForge Docs</strong>
            <p>Installation, usage examples, architecture</p>
          </a>
        </div>
      </div>

    </section>
  `,
  styles: [`
    .hero-banner {
      background: linear-gradient(135deg, #151515 0%, #2a0a0a 100%);
      color: white; padding: 48px 0;
    }
    .hero-content { display: flex; align-items: center; gap: 48px; flex-wrap: wrap; }
    .hero-text { flex: 1; min-width: 320px; }
    .hero-text h1 {
      font-family: 'Red Hat Display', sans-serif; font-size: 2rem;
      font-weight: 700; margin-bottom: 12px;
    }
    .hero-text p { color: #c9c9c9; font-size: 1.05rem; margin-bottom: 24px; line-height: 1.6; }
    .hero-text strong { color: #fff; }
    .hero-actions { display: flex; gap: 12px; flex-wrap: wrap; }
    .btn {
      display: inline-block; padding: 12px 28px; border-radius: 6px;
      font-weight: 600; font-size: 0.95rem; text-decoration: none; transition: all 0.15s;
    }
    .btn-primary { background: #ee0000; color: white; }
    .btn-primary:hover { background: #cc0000; text-decoration: none; }
    .btn-outline { border: 1px solid rgba(255,255,255,0.4); color: white; }
    .btn-outline:hover { border-color: white; background: rgba(255,255,255,0.1); text-decoration: none; }

    .hero-stats { display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px; }
    .stat-card {
      background: rgba(255,255,255,0.08); border: 1px solid rgba(255,255,255,0.12);
      border-radius: 8px; padding: 16px 20px; text-align: center; min-width: 120px;
    }
    .stat-card.highlight { border-color: rgba(238,0,0,0.5); background: rgba(238,0,0,0.1); }
    .stat-card.success { border-color: rgba(63,156,53,0.5); background: rgba(63,156,53,0.1); }
    .stat-card.muted { opacity: 0.6; }
    .stat-number { display: block; font-size: 1.8rem; font-weight: 700; font-family: 'Red Hat Display', sans-serif; }
    .stat-label { display: block; font-size: 0.8rem; color: #c9c9c9; margin-top: 2px; }

    h2 {
      font-family: 'Red Hat Display', sans-serif; font-size: 1.5rem;
      font-weight: 600; margin-bottom: 8px; color: #151515;
    }
    .section-desc { color: #6a6e73; margin-bottom: 16px; }

    .getting-started { margin-bottom: 40px; }
    .steps-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 16px; margin-top: 16px; }
    .step-card {
      display: flex; align-items: flex-start; gap: 14px; padding: 20px;
      background: white; border: 1px solid #d2d2d2; border-radius: 8px;
      text-decoration: none; color: inherit; transition: all 0.15s;
    }
    .step-card:hover { border-color: #ee0000; box-shadow: 0 2px 12px rgba(238,0,0,0.08); text-decoration: none; }
    .step-num {
      flex-shrink: 0; width: 36px; height: 36px; background: #ee0000; color: white;
      border-radius: 50%; display: flex; align-items: center; justify-content: center;
      font-weight: 700; font-size: 1rem;
    }
    .step-card h4 { font-size: 0.95rem; margin-bottom: 4px; color: #151515; }
    .step-card p { font-size: 0.85rem; color: #6a6e73; margin: 0; }

    .prompt-section { margin-bottom: 40px; }
    .prompt-chips { display: flex; flex-wrap: wrap; gap: 8px; }
    .prompt-chip {
      padding: 8px 16px; background: white; border: 1px solid #d2d2d2; border-radius: 20px;
      font-size: 0.85rem; color: #151515; text-decoration: none; transition: all 0.15s;
    }
    .prompt-chip:hover { border-color: #ee0000; color: #ee0000; text-decoration: none; }

    .projects-section { margin-bottom: 40px; }
    .section-header { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; margin-bottom: 16px; }
    .filter-bar { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; }
    .search-input {
      padding: 8px 14px; border: 1px solid #d2d2d2; border-radius: 6px;
      font-size: 0.9rem; width: 220px;
    }
    .search-input:focus { outline: none; border-color: #ee0000; }
    .toggle-label { display: flex; align-items: center; gap: 6px; font-size: 0.85rem; color: #6a6e73; cursor: pointer; }

    .project-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 12px; }
    .project-card {
      background: white; border: 1px solid #d2d2d2; border-radius: 8px; padding: 16px 20px;
      border-left: 4px solid #d2d2d2; transition: all 0.15s;
    }
    .project-card:hover { box-shadow: 0 2px 8px rgba(0,0,0,0.06); }
    .project-card.has-threescale { border-left-color: #0066cc; }
    .project-card.has-kuadrant { border-left-color: #3f9c35; }
    .project-card.has-threescale.has-kuadrant { border-left-color: #ee0000; }
    .project-header { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
    .project-header h4 { font-size: 0.95rem; font-weight: 600; color: #151515; word-break: break-all; }
    .badges { display: flex; gap: 6px; flex-shrink: 0; }
    .badge {
      padding: 2px 8px; border-radius: 10px; font-size: 0.72rem; font-weight: 600; text-transform: uppercase;
    }
    .badge-3scale { background: #e6f0ff; color: #0066cc; }
    .badge-kuadrant { background: #e6f5e0; color: #3f9c35; }
    .project-meta { display: flex; gap: 16px; margin-top: 8px; font-size: 0.82rem; color: #6a6e73; }
    .status { font-weight: 500; }
    .status.active { color: #3f9c35; }

    .loading-state { text-align: center; padding: 80px 20px; }
    .loading-state p { margin-top: 12px; color: #6a6e73; }
    .empty-state { text-align: center; padding: 40px; color: #6a6e73; }

    .links-section { margin-bottom: 24px; }
    .links-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 12px; margin-top: 16px; }
    .link-card {
      display: block; padding: 16px 20px; background: white; border: 1px solid #d2d2d2;
      border-radius: 8px; text-decoration: none; color: inherit; transition: all 0.15s;
    }
    .link-card:hover { border-color: #0066cc; text-decoration: none; }
    .link-card strong { display: block; color: #0066cc; margin-bottom: 4px; font-size: 0.95rem; }
    .link-card p { color: #6a6e73; font-size: 0.82rem; margin: 0; }
  `]
})
export class DashboardComponent implements OnInit {
  allProjects: ProjectInfo[] = [];
  userProjects: ProjectInfo[] = [];
  systemProjects: ProjectInfo[] = [];
  filteredProjects: ProjectInfo[] = [];
  loading = true;
  searchTerm = '';
  showSystem = false;

  samplePrompts = [
    'List all 3scale Products in my cluster',
    'Analyze my 3scale config and create a migration plan',
    'Generate an AuthPolicy for API Key authentication',
    'Create a RateLimitPolicy for 100 req/min',
    'Compare shared vs dedicated gateway strategies',
    'Show kuadrantctl topology'
  ];

  get threeScaleCount() { return this.userProjects.filter(p => p.hasThreeScale).length; }
  get kuadrantCount() { return this.userProjects.filter(p => p.hasKuadrant).length; }

  constructor(private api: ApiService) {}

  ngOnInit() {
    this.api.getProjects().subscribe({
      next: (data) => {
        this.allProjects = data;
        this.systemProjects = data.filter(p => this.isSystem(p.name));
        this.userProjects = data.filter(p => !this.isSystem(p.name));
        this.applyFilter();
        this.loading = false;
      },
      error: () => { this.loading = false; }
    });
  }

  applyFilter() {
    const base = this.showSystem ? this.allProjects : this.userProjects;
    const term = this.searchTerm.toLowerCase().trim();
    this.filteredProjects = term
      ? base.filter(p => p.name.toLowerCase().includes(term))
      : base;
  }

  private isSystem(name: string): boolean {
    return SYSTEM_PREFIXES.some(prefix =>
      prefix === name || name.startsWith(prefix)
    );
  }
}

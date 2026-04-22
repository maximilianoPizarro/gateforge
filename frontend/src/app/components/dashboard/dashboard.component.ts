import { Component, CUSTOM_ELEMENTS_SCHEMA, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ApiService, ProjectInfo, ThreeScaleStatus } from '../../services/api.service';

const SYSTEM_PREFIXES = ['openshift-', 'kube-', 'default', 'openshift'];

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
            <div class="stat-card api" *ngIf="adminApiStatus?.configured && adminApiStatus?.reachable">
              <span class="stat-number">{{ adminApiStatus?.productCount || 0 }}</span>
              <span class="stat-label">Admin API Products</span>
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
          <h2>Cluster Projects <span class="count-badge">{{ filteredProjects.length }}</span></h2>
          <div class="filter-bar">
            <input type="text" [(ngModel)]="searchTerm" (input)="onSearchChange()" placeholder="Filter projects..." class="search-input">
            <label class="toggle-label">
              <input type="checkbox" [(ngModel)]="showSystem" (change)="onSearchChange()">
              Show system ({{ systemProjects.length }})
            </label>
          </div>
        </div>

        <div class="project-grid">
          <div *ngFor="let project of pagedProjects" class="project-card clickable"
               [class.has-threescale]="project.hasThreeScale"
               [class.has-kuadrant]="project.hasKuadrant"
               (click)="goToChat(project)">
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
            <div class="project-action">Click to analyze with AI &rarr;</div>
          </div>
        </div>

        <div *ngIf="filteredProjects.length === 0 && !loading" class="empty-state">
          <p *ngIf="allProjects.length === 0">No projects found. Ensure the backend has cluster-admin access.</p>
          <p *ngIf="allProjects.length > 0">No projects match your filter.</p>
        </div>

        <div *ngIf="totalPages > 1" class="paginator">
          <button (click)="goToPage(currentPage - 1)" [disabled]="currentPage === 1" class="page-btn">&laquo; Prev</button>
          <button *ngFor="let p of visiblePages" (click)="goToPage(p)"
                  [class.active]="p === currentPage" class="page-btn page-num">{{ p }}</button>
          <button (click)="goToPage(currentPage + 1)" [disabled]="currentPage === totalPages" class="page-btn">Next &raquo;</button>
          <select [(ngModel)]="pageSize" (change)="onPageSizeChange()" class="page-size-select">
            <option [ngValue]="12">12 / page</option>
            <option [ngValue]="24">24 / page</option>
            <option [ngValue]="48">48 / page</option>
          </select>
        </div>
      </div>

      <div *ngIf="loading" class="loading-state">
        <div class="skeleton-grid">
          <div *ngFor="let i of [1,2,3,4]" class="skeleton-stat"></div>
        </div>
        <div class="skeleton-bar"></div>
        <div class="skeleton-grid-cards">
          <div *ngFor="let i of [1,2,3,4,5,6]" class="skeleton-card">
            <div class="skeleton-line long"></div>
            <div class="skeleton-line short"></div>
          </div>
        </div>
        <p class="loading-text">Loading cluster data...</p>
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
    .stat-card.api { border-color: rgba(0,102,204,0.5); background: rgba(0,102,204,0.1); }
    .stat-card.muted { opacity: 0.6; }
    .stat-number { display: block; font-size: 1.8rem; font-weight: 700; font-family: 'Red Hat Display', sans-serif; }
    .stat-label { display: block; font-size: 0.8rem; color: #c9c9c9; margin-top: 2px; }

    h2 {
      font-family: 'Red Hat Display', sans-serif; font-size: 1.5rem;
      font-weight: 600; margin-bottom: 8px; color: #151515;
    }
    .count-badge {
      display: inline-block; background: #e8e8e8; color: #6a6e73; font-size: 0.8rem;
      padding: 1px 10px; border-radius: 10px; margin-left: 8px; font-weight: 500;
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
      font-size: 0.9rem; width: 220px; font-family: inherit;
    }
    .search-input:focus { outline: none; border-color: #ee0000; box-shadow: 0 0 0 2px rgba(238,0,0,0.15); }
    .toggle-label { display: flex; align-items: center; gap: 6px; font-size: 0.85rem; color: #6a6e73; cursor: pointer; }

    .project-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 12px; }
    .project-card {
      background: white; border: 1px solid #d2d2d2; border-radius: 8px; padding: 16px 20px;
      border-left: 4px solid #d2d2d2; transition: all 0.2s;
    }
    .project-card.clickable { cursor: pointer; }
    .project-card.clickable:hover { border-left-color: #ee0000; box-shadow: 0 4px 16px rgba(0,0,0,0.08); transform: translateY(-1px); }
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
    .project-action {
      margin-top: 10px; font-size: 0.78rem; color: #ee0000; font-weight: 500;
      opacity: 0; transition: opacity 0.2s;
    }
    .project-card:hover .project-action { opacity: 1; }

    .paginator {
      display: flex; align-items: center; justify-content: center; gap: 4px;
      margin-top: 20px; flex-wrap: wrap;
    }
    .page-btn {
      padding: 6px 14px; border: 1px solid #d2d2d2; border-radius: 4px;
      background: white; cursor: pointer; font-size: 0.85rem; color: #151515;
      transition: all 0.15s; font-family: inherit;
    }
    .page-btn:hover:not(:disabled) { border-color: #ee0000; color: #ee0000; }
    .page-btn:disabled { opacity: 0.4; cursor: not-allowed; }
    .page-btn.page-num.active { background: #ee0000; color: white; border-color: #ee0000; }
    .page-size-select {
      margin-left: 12px; padding: 6px 8px; border: 1px solid #d2d2d2; border-radius: 4px;
      font-size: 0.85rem; font-family: inherit; cursor: pointer;
    }

    @keyframes shimmer {
      0% { background-position: -400px 0; }
      100% { background-position: 400px 0; }
    }
    .loading-state { padding: 40px 0; }
    .loading-text { text-align: center; color: #6a6e73; margin-top: 24px; }
    .skeleton-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 24px; }
    .skeleton-stat {
      height: 80px; border-radius: 8px;
      background: linear-gradient(90deg, #e8e8e8 25%, #f5f5f5 50%, #e8e8e8 75%);
      background-size: 800px 100%; animation: shimmer 1.5s infinite;
    }
    .skeleton-bar {
      height: 40px; border-radius: 6px; margin-bottom: 16px;
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
    .skeleton-line.short { width: 45%; }

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

    @media (max-width: 768px) {
      .hero-stats { grid-template-columns: repeat(2, 1fr); }
      .skeleton-grid { grid-template-columns: repeat(2, 1fr); }
    }
  `]
})
export class DashboardComponent implements OnInit {
  allProjects: ProjectInfo[] = [];
  userProjects: ProjectInfo[] = [];
  systemProjects: ProjectInfo[] = [];
  filteredProjects: ProjectInfo[] = [];
  pagedProjects: ProjectInfo[] = [];
  adminApiStatus: ThreeScaleStatus | null = null;
  loading = true;
  searchTerm = '';
  showSystem = false;

  currentPage = 1;
  pageSize = 12;
  totalPages = 1;

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

  get visiblePages(): number[] {
    const pages: number[] = [];
    let start = Math.max(1, this.currentPage - 2);
    let end = Math.min(this.totalPages, start + 4);
    start = Math.max(1, end - 4);
    for (let i = start; i <= end; i++) pages.push(i);
    return pages;
  }

  constructor(private api: ApiService, private router: Router) {}

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
    this.api.getThreeScaleStatus().subscribe({
      next: (status) => { this.adminApiStatus = status; },
      error: () => {}
    });
  }

  onSearchChange() {
    this.currentPage = 1;
    this.applyFilter();
  }

  onPageSizeChange() {
    this.currentPage = 1;
    this.updatePagination();
  }

  goToPage(page: number) {
    if (page < 1 || page > this.totalPages) return;
    this.currentPage = page;
    this.updatePagination();
  }

  goToChat(project: ProjectInfo) {
    const prompt = project.hasThreeScale
      ? `Analyze the 3scale configuration in project "${project.name}" and suggest a migration plan to Connectivity Link`
      : `List all resources in project "${project.name}" and check if there are 3scale or Kuadrant resources to migrate`;
    this.router.navigate(['/chat'], { queryParams: { q: prompt } });
  }

  private applyFilter() {
    const base = this.showSystem ? this.allProjects : this.userProjects;
    const term = this.searchTerm.toLowerCase().trim();
    this.filteredProjects = term
      ? base.filter(p => p.name.toLowerCase().includes(term))
      : [...base];
    this.updatePagination();
  }

  private updatePagination() {
    this.totalPages = Math.max(1, Math.ceil(this.filteredProjects.length / this.pageSize));
    if (this.currentPage > this.totalPages) this.currentPage = this.totalPages;
    const start = (this.currentPage - 1) * this.pageSize;
    this.pagedProjects = this.filteredProjects.slice(start, start + this.pageSize);
  }

  private isSystem(name: string): boolean {
    return SYSTEM_PREFIXES.some(prefix => prefix === name || name.startsWith(prefix));
  }
}

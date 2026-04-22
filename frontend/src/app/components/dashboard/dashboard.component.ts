import { Component, CUSTOM_ELEMENTS_SCHEMA, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { ApiService, ProjectInfo } from '../../services/api.service';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, RouterLink],
  schemas: [CUSTOM_ELEMENTS_SCHEMA],
  template: `
    <div class="container main-content">
      <h2>Cluster Dashboard</h2>
      <p>Viewing all projects across the cluster (cluster-admin)</p>

      <rh-spinner *ngIf="loading"></rh-spinner>

      <div class="card-grid" *ngIf="!loading">
        <rh-card *ngFor="let project of projects">
          <h3 slot="header">
            {{ project.name }}
            <rh-badge *ngIf="project.hasThreeScale" state="info">3scale</rh-badge>
            <rh-badge *ngIf="project.hasKuadrant" state="success">Kuadrant</rh-badge>
          </h3>
          <p>Status: {{ project.status }}</p>
          <p>Created: {{ project.creationTimestamp }}</p>
        </rh-card>
      </div>

      <div *ngIf="!loading && projects.length === 0" class="empty-state">
        <p>No projects found. Make sure the backend has cluster-admin access.</p>
      </div>
    </div>
  `,
  styles: [`
    h2 { margin-bottom: 8px; font-family: 'Red Hat Display', sans-serif; }
    p { margin-bottom: 16px; color: #6a6e73; }
    .empty-state { padding: 48px; text-align: center; }
    rh-badge { margin-left: 8px; }
  `]
})
export class DashboardComponent implements OnInit {
  projects: ProjectInfo[] = [];
  loading = true;

  constructor(private api: ApiService) {}

  ngOnInit() {
    this.api.getProjects().subscribe({
      next: (data) => { this.projects = data; this.loading = false; },
      error: () => { this.loading = false; }
    });
  }
}

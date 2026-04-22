import { Component, CUSTOM_ELEMENTS_SCHEMA } from '@angular/core';
import { RouterOutlet, RouterLink, RouterLinkActive } from '@angular/router';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  schemas: [CUSTOM_ELEMENTS_SCHEMA],
  template: `
    <header class="page-header">
      <div class="container header-content">
        <svg class="logo" viewBox="0 0 40 40" xmlns="http://www.w3.org/2000/svg">
          <rect width="40" height="40" rx="8" fill="#EE0000"/>
          <path d="M8 28 L20 8 L32 28 Z" fill="none" stroke="white" stroke-width="2.5" stroke-linejoin="round"/>
          <circle cx="20" cy="22" r="3" fill="white"/>
        </svg>
        <h1>GateForge</h1>
        <nav>
          <a routerLink="/" routerLinkActive="active" [routerLinkActiveOptions]="{exact: true}">Dashboard</a>
          <a routerLink="/threescale" routerLinkActive="active">3scale Explorer</a>
          <a routerLink="/migrate" routerLinkActive="active">Migration</a>
          <a routerLink="/chat" routerLinkActive="active">AI Chat</a>
          <a routerLink="/audit" routerLinkActive="active">Audit Log</a>
        </nav>
      </div>
    </header>

    <main>
      <router-outlet></router-outlet>
    </main>

    <footer class="site-footer">
      <div class="container">
        <div class="footer-links">
          <a href="https://docs.redhat.com/en/documentation/red_hat_connectivity_link" target="_blank">Connectivity Link Docs</a>
          <a href="https://docs.kuadrant.io/" target="_blank">Kuadrant Docs</a>
          <a href="https://github.com/Kuadrant/kuadrantctl" target="_blank">kuadrantctl</a>
          <a href="https://docs.redhat.com/en/documentation/red_hat_3scale_api_management" target="_blank">3scale Docs</a>
          <a href="https://gateway-api.sigs.k8s.io/" target="_blank">Gateway API</a>
          <a href="https://github.com/maximilianoPizarro/gateforge" target="_blank">GitHub</a>
        </div>
        <p class="copyright">GateForge &copy; 2026 &mdash; Apache 2.0 License</p>
      </div>
    </footer>
  `,
  styles: [`
    .page-header {
      background: #151515; color: white; padding: 0;
    }
    .header-content {
      display: flex; align-items: center; gap: 12px; padding: 12px 24px;
      max-width: 1280px; margin: 0 auto;
    }
    .logo { height: 32px; width: 32px; }
    h1 {
      font-family: 'Red Hat Display', sans-serif;
      font-size: 1.2rem; font-weight: 500; margin-right: 24px;
    }
    nav { display: flex; gap: 4px; }
    nav a {
      color: #c9c9c9; text-decoration: none; padding: 8px 14px;
      border-radius: 4px; font-size: 0.9rem; transition: all 0.15s;
      &:hover { color: white; background: rgba(255,255,255,0.1); }
      &.active { color: white; background: #ee0000; }
    }
    main { min-height: calc(100vh - 140px); }
    .site-footer {
      background: #151515; color: #c9c9c9; padding: 24px 0; margin-top: 48px;
    }
    .footer-links {
      display: flex; flex-wrap: wrap; gap: 16px; margin-bottom: 12px;
      a { color: #73bcf7; font-size: 0.9em; }
    }
    .copyright { font-size: 0.8em; color: #6a6e73; }
  `]
})
export class AppComponent {}

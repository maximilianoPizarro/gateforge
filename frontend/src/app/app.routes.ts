import { Routes } from '@angular/router';
import { DashboardComponent } from './components/dashboard/dashboard.component';
import { ThreeScaleExplorerComponent } from './components/threescale-explorer/threescale-explorer.component';
import { MigrationWizardComponent } from './components/migration-wizard/migration-wizard.component';
import { ChatComponent } from './components/chat/chat.component';
import { AuditLogComponent } from './components/audit-log/audit-log.component';
import { SettingsComponent } from './components/settings/settings.component';

export const routes: Routes = [
  { path: '', component: DashboardComponent },
  { path: 'threescale', component: ThreeScaleExplorerComponent },
  { path: 'migrate', component: MigrationWizardComponent },
  { path: 'chat', component: ChatComponent },
  { path: 'audit', component: AuditLogComponent },
  { path: 'settings', component: SettingsComponent },
  { path: '**', redirectTo: '' }
];

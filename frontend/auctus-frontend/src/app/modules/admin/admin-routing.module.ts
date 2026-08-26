import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';
import { DashboardComponent } from './dashboard/dashboard.component';
import { ArchiveComponent } from './archive/archive.component';
import { AgentsComponent } from './agents/agents.component';
import { ClientsComponent } from '../../shared/clients/clients.component';
import { MessagesComponent } from '../../shared/messages/messages.component';
import { ScheduleComponent } from '../../shared/schedule/schedule.component';

const routes: Routes = [
  { path: '', redirectTo: 'dashboard', pathMatch: 'full' },
  { path: 'dashboard', component: DashboardComponent },
  // The validation record and account management were sections at the bottom of
  // the console; on their own pages they can carry search, filters and paging.
  { path: 'archive', component: ArchiveComponent },
  { path: 'agents', component: AgentsComponent },
  { path: 'clients', component: ClientsComponent },
  { path: 'messages', component: MessagesComponent },
  { path: 'schedule', component: ScheduleComponent }
];

@NgModule({
  imports: [RouterModule.forChild(routes)],
  exports: [RouterModule]
})
export class AdminRoutingModule { }

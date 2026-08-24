import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';
import { DashboardComponent } from './dashboard/dashboard.component';
import { HistoryComponent } from './history/history.component';
import { ProfileComponent } from './profile/profile.component';
import { ClientsComponent } from '../../shared/clients/clients.component';
import { MessagesComponent } from '../../shared/messages/messages.component';
import { ScheduleComponent } from '../../shared/schedule/schedule.component';

const routes: Routes = [
  { path: '', component: DashboardComponent },
  { path: 'dashboard', component: DashboardComponent },
  { path: 'history', component: HistoryComponent },
  { path: 'clients', component: ClientsComponent },
  { path: 'profile', component: ProfileComponent },
  { path: 'schedule', component: ScheduleComponent },
  { path: 'conversations', component: MessagesComponent },
  // The archive view is the same record as the history, just entered elsewhere.
  { path: 'archive', redirectTo: 'history', pathMatch: 'full' }
];

@NgModule({
  imports: [RouterModule.forChild(routes)],
  exports: [RouterModule]
})
export class AgentRoutingModule { }

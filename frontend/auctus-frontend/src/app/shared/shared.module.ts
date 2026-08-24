import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClientModule } from '@angular/common/http';
import { RouterModule } from '@angular/router';
import { ClientsComponent } from './clients/clients.component';
import { MessagesComponent } from './messages/messages.component';
import { ScheduleComponent } from './schedule/schedule.component';
import { TiltDirective } from './directives/tilt.directive';
import { ParallaxDirective } from './directives/parallax.directive';

/**
 * Pieces used by more than one role. The client register, the messaging thread and
 * the agenda are all consulted by agents and administrators alike, so they live
 * here rather than inside either dashboard.
 *
 * The interaction directives are standalone, so they are imported rather than
 * declared, and re-exported so any module that pulls in SharedModule can use
 * them on its own templates.
 */
@NgModule({
  declarations: [
    ClientsComponent,
    MessagesComponent,
    ScheduleComponent
  ],
  imports: [
    CommonModule,
    FormsModule,
    HttpClientModule,
    RouterModule,
    TiltDirective,
    ParallaxDirective
  ],
  exports: [
    ClientsComponent,
    MessagesComponent,
    ScheduleComponent,
    TiltDirective,
    ParallaxDirective
  ]
})
export class SharedModule { }

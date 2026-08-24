import { NgModule } from '@angular/core';
import { BrowserModule } from '@angular/platform-browser';
import { HttpClientModule } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { AppRoutingModule } from './app-routing.module';
import { AppComponent } from './app.component';
import { LoginComponent } from './modules/auth/login/login.component';
import { HeroShieldComponent } from './shared/hero-shield/hero-shield.component';

@NgModule({
  declarations: [
    AppComponent,
    LoginComponent
  ],
  imports: [
    BrowserModule,
    AppRoutingModule,
    HttpClientModule,
    FormsModule,
    // Standalone. It resolves `three` with a dynamic import at runtime, so
    // importing it here does not pull the library into the main bundle.
    HeroShieldComponent
  ],
  providers: [],
  bootstrap: [AppComponent]
})
export class AppModule { }
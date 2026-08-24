import { Component, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { environment } from '../../../../environments/environment';

@Component({
  selector: 'app-login',
  templateUrl: './login.component.html',
  styleUrls: ['./login.component.css']
})
export class LoginComponent implements OnInit {
  email: string = '';
  password: string = '';
  rememberMe: boolean = false;
  isLoading: boolean = false;
  errorMessage: string = '';
  showPassword: boolean = false;

  constructor(
    private http: HttpClient,
    private router: Router
  ) {}

  ngOnInit(): void {
    this.checkExistingSession();
  }

  checkExistingSession(): void {
    const token = localStorage.getItem('token');
    const user = localStorage.getItem('user');
    
    if (token && user) {
      const userData = JSON.parse(user);
      if (userData.role === 'AGENT') {
        this.router.navigate(['/agent/dashboard']);
      } else if (userData.role === 'ADMIN') {
        this.router.navigate(['/admin/dashboard']);
      }
    }
  }

  togglePasswordVisibility(): void {
    this.showPassword = !this.showPassword;
  }

  onSubmit(): void {
    if (!this.email || !this.password) {
      this.errorMessage = 'Veuillez remplir tous les champs';
      return;
    }

    this.isLoading = true;
    this.errorMessage = '';

    console.log('Login attempt for:', this.email);

    this.http.post(`${environment.authUrl}/api/auth/login`, {
      email: this.email,
      password: this.password
    }).subscribe({
      next: (response: any) => {
        console.log('Login response:', response);
        
        // Save token in multiple formats for compatibility
        const token = response.token || response.accessToken;
        localStorage.setItem('token', token);
        localStorage.setItem('access_token', token);
        
        // Get user ID from email or response
        const userId = this.getUserIdFromEmail(this.email);
        
        const userData = {
          id: response.userId || response.id || userId,
          email: response.email || this.email,
          firstName: response.firstName || this.getFirstNameFromEmail(this.email),
          lastName: response.lastName || this.getLastNameFromEmail(this.email),
          role: response.role || 'AGENT',
          fullName: `${response.firstName || this.getFirstNameFromEmail(this.email)} ${response.lastName || this.getLastNameFromEmail(this.email)}`
        };
        
        localStorage.setItem('user', JSON.stringify(userData));
        
        console.log('Saved user data:', userData);
        console.log('Saved token:', token);
        
        this.isLoading = false;
        
        // Redirect based on role
        if (userData.role === 'ADMIN') {
          this.router.navigate(['/admin/dashboard']);
        } else {
          this.router.navigate(['/agent/dashboard']);
        }
      },
      error: (error) => {
        console.error('Login error:', error);
        this.isLoading = false;
        
        if (error.status === 401) {
          this.errorMessage = 'Email ou mot de passe incorrect';
        } else if (error.status === 0) {
          this.errorMessage = 'Impossible de contacter le serveur';
          // For testing without backend, create a demo session
          if (confirm('Backend not available. Create demo session for testing?')) {
            this.createDemoSession();
          }
        } else if (error.error && error.error.message) {
          this.errorMessage = error.error.message;
        } else {
          this.errorMessage = 'Erreur de connexion';
        }
      }
    });
  }

  createDemoSession(): void {
    // Create a demo session for testing without backend
    const demoToken = 'demo_token_' + Date.now();
    localStorage.setItem('token', demoToken);
    localStorage.setItem('access_token', demoToken);
    
    const userData = {
      id: this.getUserIdFromEmail(this.email),
      email: this.email,
      firstName: this.getFirstNameFromEmail(this.email),
      lastName: this.getLastNameFromEmail(this.email),
      role: 'AGENT',
      fullName: `${this.getFirstNameFromEmail(this.email)} ${this.getLastNameFromEmail(this.email)}`
    };
    
    localStorage.setItem('user', JSON.stringify(userData));
    this.router.navigate(['/agent/dashboard']);
  }

  private getUserIdFromEmail(email: string): string {
    const emailToId: { [key: string]: string } = {
      'agent@auctus.com': '1',
      'hamdi@auctus.com': '2',
      'nader@auctus.com': '3',
      'zeineb@auctus.com': '4'
    };
    return emailToId[email] || email.split('@')[0];
  }

  private getFirstNameFromEmail(email: string): string {
    const emailToFirstName: { [key: string]: string } = {
      'agent@auctus.com': 'Ahmed',
      'hamdi@auctus.com': 'Hamdi',
      'nader@auctus.com': 'Nader',
      'zeineb@auctus.com': 'Zeineb'
    };
    return emailToFirstName[email] || email.split('@')[0];
  }

  private getLastNameFromEmail(email: string): string {
    const emailToLastName: { [key: string]: string } = {
      'agent@auctus.com': 'Ben Ahmed',
      'hamdi@auctus.com': 'Malek',
      'nader@auctus.com': 'Rahman',
      'zeineb@auctus.com': 'Maatoug'
    };
    return emailToLastName[email] || 'User';
  }
}
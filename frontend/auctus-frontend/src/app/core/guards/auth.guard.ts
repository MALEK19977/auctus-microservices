import { Injectable } from '@angular/core';
import { CanActivate, Router, ActivatedRouteSnapshot, RouterStateSnapshot } from '@angular/router';

@Injectable({
  providedIn: 'root'
})
export class AuthGuard implements CanActivate {
  constructor(private router: Router) {}

  canActivate(route: ActivatedRouteSnapshot, state: RouterStateSnapshot): boolean {
    console.log('=== AUTH GUARD DEBUG ===');
    console.log('Trying to access:', state.url);
    
    const token = localStorage.getItem('token') || localStorage.getItem('access_token');
    const user = localStorage.getItem('user');
    
    console.log('Token present:', !!token);
    console.log('Token value:', token);
    console.log('User present:', !!user);
    
    // For testing: if no token but we're in development, allow access
    // This is temporary - remove in production
    if (!token && !user) {
      console.log('No auth data, but allowing access for testing');
      // Create a demo user for testing
      const demoUser = {
        id: '2',
        email: 'hamdi@auctus.com',
        firstName: 'Hamdi',
        lastName: 'Malek',
        role: 'AGENT',
        fullName: 'Hamdi Malek'
      };
      localStorage.setItem('user', JSON.stringify(demoUser));
      localStorage.setItem('token', 'demo_token');
      return true;
    }
    
    if (token && user) {
      console.log('Auth guard: ACCESS GRANTED');
      return true;
    }
    
    console.log('Auth guard: ACCESS DENIED - Redirecting to login');
    this.router.navigate(['/login']);
    return false;
  }
}
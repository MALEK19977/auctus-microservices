import { Component, OnInit } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';

@Component({
  selector: 'app-profile',
  templateUrl: './profile.component.html',
  styleUrls: ['./profile.component.css']
})
export class ProfileComponent implements OnInit {

  private readonly CHEQUE_API = 'http://localhost:8082/api/cheque';
  private readonly AUTH_API = 'http://localhost:8081/api/auth';

  userName = '';
  userId = '';
  userEmail = '';
  userRole = '';

  account: any = null;
  stats: any = { total: 0, accepted: 0, review: 0, rejected: 0, averageProcessingTime: 0, averageSignatureScore: 0 };
  loading = true;

  constructor(private http: HttpClient, public router: Router) {}

  ngOnInit(): void {
    const userStr = localStorage.getItem('user');
    if (userStr) {
      const user = JSON.parse(userStr);
      this.userName = `${user.firstName} ${user.lastName}`;
      this.userId = user.id;
      this.userEmail = user.email;
      this.userRole = user.role;
    }

    this.http.get<any>(`${this.CHEQUE_API}/statistics`, { params: { agentId: this.userId } })
      .subscribe({
        next: data => { this.stats = data; this.loading = false; },
        error: () => { this.loading = false; }
      });

    // The account record carries the last sign-in, which the token does not.
    this.http.get<any>(`${this.AUTH_API}/users`).subscribe({
      next: data => {
        this.account = (data.users || []).find((u: any) => u.email === this.userEmail) || null;
      },
      error: () => { this.account = null; }
    });
  }

  get acceptanceRate(): number {
    return this.stats.total ? Math.round((this.stats.accepted / this.stats.total) * 100) : 0;
  }

  getInitials(): string {
    return (this.userName || 'AG').split(' ').filter(p => p).map(p => p[0]).join('').toUpperCase().slice(0, 2);
  }

  goBack(): void { this.router.navigate(['/agent/dashboard']); }
}

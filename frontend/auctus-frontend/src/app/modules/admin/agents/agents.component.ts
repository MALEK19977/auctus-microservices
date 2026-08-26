import { Component, OnInit } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';

/**
 * The people who use the platform: who they are, what they have been doing, and
 * how to add or suspend one.
 *
 * Account management used to be a table at the bottom of the console with a
 * single Suspend button and no way to create anyone, so a new agent had to be
 * inserted into the database by hand.
 */
@Component({
  selector: 'app-admin-agents',
  templateUrl: './agents.component.html',
  styleUrls: ['./agents.component.css']
})
export class AgentsComponent implements OnInit {

  private readonly AUTH_API = 'http://localhost:8081/api/auth';
  private readonly CHEQUE_API = 'http://localhost:8082/api/cheque';

  userName = '';

  users: any[] = [];
  /** Validation counts per agent, merged onto the account rows. */
  activity: Record<string, any> = {};

  loading = true;
  error = '';
  notice = '';

  search = '';
  roleFilter: 'ALL' | 'ADMIN' | 'AGENT' = 'ALL';
  showInactive = true;

  // create form
  creating = false;
  saving = false;
  form = { firstName: '', lastName: '', email: '', password: '', role: 'AGENT' };

  constructor(private http: HttpClient, public router: Router) {}

  ngOnInit(): void {
    const userStr = localStorage.getItem('user');
    if (userStr) {
      const user = JSON.parse(userStr);
      this.userName = `${user.firstName} ${user.lastName}`;
    }
    this.load();
  }

  load(): void {
    this.loading = true;
    this.http.get<any>(`${this.AUTH_API}/users`).subscribe({
      next: data => {
        this.users = data.users || [];
        this.loading = false;
      },
      error: () => {
        this.error = 'Auth service unreachable (port 8081).';
        this.users = [];
        this.loading = false;
      }
    });

    // Volume per agent, so an account row says whether it is actually in use.
    this.http.get<any>(`${this.CHEQUE_API}/statistics`, { params: { days: '365' } }).subscribe({
      next: data => {
        const map: Record<string, any> = {};
        for (const row of data.byAgent || []) {
          map[row.agentId] = row;
        }
        this.activity = map;
      },
      error: () => { this.activity = {}; }
    });
  }

  get filtered(): any[] {
    const term = this.search.trim().toLowerCase();
    return this.users.filter(u => {
      if (this.roleFilter !== 'ALL' && u.role !== this.roleFilter) { return false; }
      if (!this.showInactive && u.status !== 'ACTIVE') { return false; }
      if (!term) { return true; }
      return [u.email, u.firstName, u.lastName, u.role]
        .filter(Boolean).join(' ').toLowerCase().includes(term);
    });
  }

  get activeCount(): number {
    return this.users.filter(u => u.status === 'ACTIVE').length;
  }

  /** What this account has validated, if anything. */
  work(user: any): any {
    return this.activity[user.id] || null;
  }

  // ------------------------------------------------------------ create

  get formError(): string {
    if (!this.form.firstName.trim() || !this.form.lastName.trim()) {
      return 'A first and last name are needed — an agent is a person, not an inbox.';
    }
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(this.form.email.trim())) {
      return 'That email address does not look valid.';
    }
    if (this.form.password.length < 10) {
      return `The password must be at least 10 characters (currently ${this.form.password.length}).`;
    }
    if (this.users.some(u => (u.email || '').toLowerCase() === this.form.email.trim().toLowerCase())) {
      return 'An account already exists with that email.';
    }
    return '';
  }

  create(): void {
    if (this.formError) { return; }
    this.saving = true;
    this.error = '';

    this.http.post<any>(`${this.AUTH_API}/users`, {
      firstName: this.form.firstName.trim(),
      lastName: this.form.lastName.trim(),
      email: this.form.email.trim(),
      password: this.form.password,
      role: this.form.role
    }).subscribe({
      next: created => {
        this.notice = `${created.firstName} ${created.lastName} can now sign in as ${created.email}.`;
        this.form = { firstName: '', lastName: '', email: '', password: '', role: 'AGENT' };
        this.creating = false;
        this.saving = false;
        this.load();
      },
      error: err => {
        this.error = err?.error?.error
          || (err?.status === 0
              ? 'Auth service unreachable on port 8081.'
              : `The account could not be created (HTTP ${err?.status}).`);
        this.saving = false;
      }
    });
  }

  /** A readable password the admin can hand over, rather than inventing one. */
  suggestPassword(): void {
    const words = ['Auctus', 'Zitouna', 'Carthage', 'Hannibal', 'Medina', 'Sahara'];
    const word = words[Math.floor(Math.random() * words.length)];
    const digits = Math.floor(1000 + Math.random() * 9000);
    this.form.password = `${word}@${digits}!`;
  }

  // ------------------------------------------------------------ actions

  toggle(user: any): void {
    const status = user.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE';
    this.http.patch<any>(`${this.AUTH_API}/users/${user.id}`, { status }).subscribe({
      next: updated => {
        user.status = updated.status;
        this.notice = `${user.firstName} ${user.lastName} is now ${status.toLowerCase()}.`;
      },
      error: () => { this.error = 'The account status could not be changed.'; }
    });
  }

  changeRole(user: any, role: string): void {
    this.http.patch<any>(`${this.AUTH_API}/users/${user.id}`, { role }).subscribe({
      next: updated => {
        user.role = updated.role;
        this.notice = `${user.firstName} ${user.lastName} is now ${role}.`;
      },
      error: () => { this.error = 'The role could not be changed.'; }
    });
  }

  initials(user: any): string {
    return `${(user.firstName || '?')[0]}${(user.lastName || '')[0] || ''}`.toUpperCase();
  }

  back(): void { this.router.navigate(['/admin/dashboard']); }
}

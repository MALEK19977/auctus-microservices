import { Component, OnDestroy, OnInit } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { Subscription, interval } from 'rxjs';

interface Bucket {
  date: string;
  label: string;
  accepted: number;
  review: number;
  rejected: number;
  total: number;
}

interface Bar {
  label: string;
  value: number;
  width: number;
  sub?: string;
}

/** One stacked column, already resolved to pixel geometry for the SVG. */
interface Column {
  key: string;
  label: string;
  x: number;
  total: number;
  segments: { y: number; height: number; fill: string; status: string; count: number }[];
}

interface RangeOption {
  id: string;
  label: string;
  hours?: number;
  days?: number;
  axis: string;
}

@Component({
  selector: 'app-dashboard',
  templateUrl: './dashboard.component.html',
  styleUrls: ['./dashboard.component.css']
})
export class DashboardComponent implements OnInit, OnDestroy {

  private readonly CHEQUE_API = 'http://localhost:8082/api/cheque';
  private readonly AUTH_API = 'http://localhost:8081/api/auth';

  // Status palette - validated against a light surface with the dataviz checks.
  // Reserved for state; never reused as a generic series colour.
  readonly COLOR = {
    accepted: '#10B981',
    review: '#F59E0B',
    rejected: '#EF4444',
    brand: '#C6A43F'
  };

  readonly ranges: RangeOption[] = [
    { id: '2h',  label: 'Last 2 hours',  hours: 2,  axis: 'Hour of day' },
    { id: '4h',  label: 'Last 4 hours',  hours: 4,  axis: 'Hour of day' },
    { id: '24h', label: 'Last 24 hours', hours: 24, axis: 'Hour of day' },
    { id: '7d',  label: 'Last 7 days',   days: 7,   axis: 'Date (day/month)' },
    { id: '14d', label: 'Last 14 days',  days: 14,  axis: 'Date (day/month)' },
    { id: '30d', label: 'Last 30 days',  days: 30,  axis: 'Date (day/month)' }
  ];
  selectedRange = '14d';
  selectedAgent = 'ALL';

  userName = '';
  userId = '';

  loading = true;
  loadError = '';
  lastUpdated: Date | null = null;

  stats: any = {
    total: 0, accepted: 0, review: 0, rejected: 0,
    today: 0, averageProcessingTime: 0, averageSignatureScore: 0,
    totalPlafondAccepted: 0
  };

  series: Bucket[] = [];
  agents: any[] = [];
  agentOptions: { agentId: string; name: string }[] = [];
  rejectionReasons: { reason: string; count: number }[] = [];

  users: any[] = [];
  recent: any[] = [];
  expanded: string | null = null;

  showTrendTable = false;
  showAgentTable = false;

  // chart geometry
  readonly chartW = 760;
  readonly chartH = 250;
  readonly padL = 52;
  readonly padB = 48;
  readonly padT = 14;

  columns: Column[] = [];
  yTicks: { y: number; value: number }[] = [];
  agentBars: Bar[] = [];
  reasonBars: Bar[] = [];
  hovered: Column | null = null;

  private refresh: Subscription | null = null;

  /** Badge on the Messages button, refreshed with the rest of the console. */
  unreadMessages = 0;

  constructor(private http: HttpClient, public router: Router) {}

  ngOnInit(): void {
    const userStr = localStorage.getItem('user');
    if (userStr) {
      const user = JSON.parse(userStr);
      this.userName = `${user.firstName} ${user.lastName}`;
      this.userId = user.id;
    }
    this.loadAll();
    // Every validation an agent performs lands in the same database, so polling
    // keeps this console in step with the floor without any push channel.
    this.refresh = interval(5000).subscribe(() => this.loadAll(true));
  }

  ngOnDestroy(): void {
    this.refresh?.unsubscribe();
  }

  get range(): RangeOption {
    return this.ranges.find(r => r.id === this.selectedRange) || this.ranges[4];
  }

  get axisLabel(): string { return this.range.axis; }

  onFilterChange(): void {
    this.loadAll();
  }

  loadAll(silent = false): void {
    // A background poll must not flash the loading banner over live figures.
    this.loading = !silent;
    this.loadError = '';

    const params: any = {};
    if (this.range.hours) { params.hours = String(this.range.hours); }
    if (this.range.days) { params.days = String(this.range.days); }
    if (this.selectedAgent !== 'ALL') { params.agentId = this.selectedAgent; }

    this.http.get<any>(`${this.CHEQUE_API}/statistics`, { params }).subscribe({
      next: data => {
        this.stats = data;
        this.series = data.series || [];
        this.agents = data.byAgent || [];
        this.rejectionReasons = data.rejectionReasons || [];
        this.agentOptions = data.agents || [];
        this.buildTrend();
        this.buildAgentBars();
        this.buildReasonBars();
        this.lastUpdated = new Date();
        this.loading = false;
      },
      error: () => {
        this.loadError = 'Cheque service unreachable (port 8082).';
        this.loading = false;
      }
    });

    const historyParams: any = { size: '12' };
    if (this.selectedAgent !== 'ALL') { historyParams.agentId = this.selectedAgent; }
    this.http.get<any>(`${this.CHEQUE_API}/history`, { params: historyParams }).subscribe({
      next: data => { this.recent = data.content || []; },
      error: () => { this.recent = []; }
    });

    this.http.get<any>(`${this.AUTH_API}/users`).subscribe({
      next: data => { this.users = data.users || []; },
      error: () => { this.users = []; }
    });

    if (this.userId) {
      this.http.get<any>('http://localhost:8087/api/messages/unread', {
        params: { userId: this.userId }
      }).subscribe({
        next: data => { this.unreadMessages = data.unread || 0; },
        error: () => { /* the badge simply stays as it was */ }
      });
    }
  }

  // ---------------------------------------------------------------- charts

  private buildTrend(): void {
    const plotH = this.chartH - this.padB - this.padT;
    const max = Math.max(1, ...this.series.map(d => d.total));
    const step = (this.chartW - this.padL - 8) / Math.max(1, this.series.length);
    const barW = Math.min(34, step * 0.6);

    const tickValues = max <= 4
      ? Array.from({ length: max + 1 }, (_, i) => i)
      : [0, Math.round(max / 2), max];

    this.yTicks = tickValues
      .filter((v, i, arr) => arr.indexOf(v) === i)
      .map(value => ({ value, y: this.padT + plotH - (value / max) * plotH }));

    this.columns = this.series.map((bucket, index) => {
      const x = this.padL + index * step + (step - barW) / 2;
      const parts = [
        { status: 'Validated', count: bucket.accepted, fill: this.COLOR.accepted },
        { status: 'Needs review', count: bucket.review, fill: this.COLOR.review },
        { status: 'Rejected', count: bucket.rejected, fill: this.COLOR.rejected }
      ].filter(p => p.count > 0);

      let cursor = this.padT + plotH;
      const segments = parts.map(part => {
        const height = (part.count / max) * plotH;
        cursor -= height;
        return {
          y: cursor,
          // 2px surface gap between stacked segments.
          height: Math.max(2, height - 2),
          fill: part.fill,
          status: part.status,
          count: part.count
        };
      });

      return {
        key: bucket.date,
        label: bucket.label || bucket.date.slice(5),
        x,
        total: bucket.total,
        segments
      };
    });
  }

  get barWidth(): number {
    const step = (this.chartW - this.padL - 8) / Math.max(1, this.series.length);
    return Math.min(34, step * 0.6);
  }

  /** Thin out x labels so they never collide on a 30-day range. */
  showLabel(index: number): boolean {
    const every = this.series.length > 20 ? 5 : this.series.length > 12 ? 2 : 1;
    return index % every === 0 || index === this.series.length - 1;
  }

  private buildAgentBars(): void {
    const max = Math.max(1, ...this.agents.map(a => a.total));
    this.agentBars = this.agents.slice(0, 8).map(a => ({
      label: a.agentName || a.agentId,
      value: a.total,
      width: (a.total / max) * 100,
      sub: `${a.accepted} validated · ${a.rejected} rejected`
    }));
  }

  private buildReasonBars(): void {
    const max = Math.max(1, ...this.rejectionReasons.map(r => r.count));
    this.reasonBars = this.rejectionReasons.slice(0, 8).map(r => ({
      label: r.reason,
      value: r.count,
      width: (r.count / max) * 100
    }));
  }

  // ---------------------------------------------------------------- actions

  toggleDetail(cheque: any): void {
    this.expanded = this.expanded === cheque.id ? null : cheque.id;
  }

  decide(cheque: any, decision: 'ACCEPTED' | 'REJECTED'): void {
    this.http.post<any>(`${this.CHEQUE_API}/${cheque.id}/decision`, {
      decision,
      reviewer: this.userName || 'admin',
      note: 'Reviewed from the admin console'
    }).subscribe({
      next: () => this.loadAll(),
      error: () => { this.loadError = 'The decision could not be saved.'; }
    });
  }

  toggleUser(user: any): void {
    const status = user.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE';
    this.http.patch<any>(`${this.AUTH_API}/users/${user.id}`, { status }).subscribe({
      next: updated => { user.status = updated.status; },
      error: () => { this.loadError = 'The account status could not be changed.'; }
    });
  }

  // ---------------------------------------------------------------- helpers

  get acceptanceRate(): number {
    return this.stats.total ? Math.round((this.stats.accepted / this.stats.total) * 100) : 0;
  }

  agentName(cheque: any): string {
    if (cheque.validatedByName) { return cheque.validatedByName; }
    const id = cheque.validatedBy;
    return id && /^\d+$/.test(id) ? `Agent ${id}` : (id || '—');
  }

  statusClass(status: string): string {
    switch ((status || '').toUpperCase()) {
      case 'ACCEPTED': return 'badge-success';
      case 'REJECTED': return 'badge-error';
      case 'REVIEW': return 'badge-warning';
      default: return 'badge-neutral';
    }
  }

  money(value: any): string {
    return Number(value || 0).toLocaleString('en-US', { maximumFractionDigits: 0 });
  }

  goToClients(): void { this.router.navigate(['/admin/clients']); }
  goToMessages(): void { this.router.navigate(['/admin/messages']); }
  goToSchedule(): void { this.router.navigate(['/admin/schedule']); }

  logout(): void {
    localStorage.removeItem('token');
    localStorage.removeItem('access_token');
    localStorage.removeItem('user');
    this.router.navigate(['/login']);
  }
}

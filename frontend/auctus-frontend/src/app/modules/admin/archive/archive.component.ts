import { Component, OnInit } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';

/**
 * Every validation the branch has made, with the tools to find one.
 *
 * This used to sit at the bottom of the console, which meant an administrator
 * scrolled past the whole dashboard to reach it and then scrolled the list
 * itself to find a cheque. On its own page it can carry a search box, filters
 * and paging, and the console stays about deciding rather than browsing.
 */
@Component({
  selector: 'app-admin-archive',
  templateUrl: './archive.component.html',
  styleUrls: ['./archive.component.css']
})
export class ArchiveComponent implements OnInit {

  private readonly CHEQUE_API = 'http://localhost:8082/api/cheque';
  private readonly CLIENT_API = 'http://localhost:8086/api/client';

  userName = '';

  rows: any[] = [];
  filtered: any[] = [];
  page: any[] = [];

  loading = true;
  loadError = '';

  // filters
  search = '';
  status: 'ALL' | 'ACCEPTED' | 'REJECTED' = 'ALL';
  agent = 'ALL';
  onlyUnreviewed = false;
  agentOptions: { id: string; name: string }[] = [];

  // paging
  pageIndex = 0;
  pageSize = 25;
  readonly pageSizes = [25, 50, 100];

  expanded: string | null = null;
  /** Client behind the expanded row, fetched when the row opens. */
  client: any = null;
  clientLoading = false;

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
    this.http.get<any>(`${this.CHEQUE_API}/history`, { params: { size: '1000' } }).subscribe({
      next: data => {
        this.rows = data.content || [];
        this.agentOptions = this.distinctAgents();
        this.applyFilters();
        this.loading = false;
      },
      error: () => {
        this.loadError = 'Cheque service unreachable (port 8082).';
        this.rows = [];
        this.applyFilters();
        this.loading = false;
      }
    });
  }

  private distinctAgents(): { id: string; name: string }[] {
    const seen = new Map<string, string>();
    for (const row of this.rows) {
      if (row.validatedBy && !seen.has(row.validatedBy)) {
        seen.set(row.validatedBy, row.validatedByName || row.validatedBy);
      }
    }
    return [...seen.entries()].map(([id, name]) => ({ id, name }))
      .sort((a, b) => a.name.localeCompare(b.name));
  }

  applyFilters(): void {
    const term = this.search.trim().toLowerCase().replace(/\s/g, '');

    this.filtered = this.rows.filter(row => {
      if (this.status !== 'ALL' && row.status !== this.status) { return false; }
      if (this.agent !== 'ALL' && row.validatedBy !== this.agent) { return false; }
      if (this.onlyUnreviewed && (row.status !== 'REJECTED' || row.reviewedAt)) { return false; }
      if (!term) { return true; }

      // One box for whatever the administrator has to hand: a cheque number, a
      // holder, a RIB, the agent, or words from the rejection reason.
      const haystack = [
        row.chequeNumber, row.issuerName, row.issuerRib, row.beneficiaryRib,
        row.validatedByName, row.validatedBy, row.rejectionReason
      ].filter(Boolean).join(' ').toLowerCase().replace(/\s/g, '');

      return haystack.includes(term);
    });

    this.pageIndex = 0;
    this.paginate();
  }

  paginate(): void {
    const start = this.pageIndex * this.pageSize;
    this.page = this.filtered.slice(start, start + Number(this.pageSize));
  }

  get totalPages(): number {
    return Math.max(1, Math.ceil(this.filtered.length / this.pageSize));
  }

  goPage(delta: number): void {
    const next = this.pageIndex + delta;
    if (next >= 0 && next < this.totalPages) {
      this.pageIndex = next;
      this.paginate();
    }
  }

  clearFilters(): void {
    this.search = '';
    this.status = 'ALL';
    this.agent = 'ALL';
    this.onlyUnreviewed = false;
    this.applyFilters();
  }

  /** Rejections nobody has looked at yet - what the console badge counts. */
  get unreviewedCount(): number {
    return this.rows.filter(r => r.status === 'REJECTED' && !r.reviewedAt).length;
  }

  toggleDetail(row: any): void {
    this.expanded = this.expanded === row.id ? null : row.id;
    this.client = null;

    if (this.expanded && row.issuerRib) {
      this.clientLoading = true;
      this.http.get<any>(`${this.CLIENT_API}/rib/${row.issuerRib}`).subscribe({
        next: data => { this.client = data; this.clientLoading = false; },
        error: () => { this.client = null; this.clientLoading = false; }
      });
    }

    // Opening a rejection is what counts as having seen it.
    if (this.expanded && row.status === 'REJECTED' && !row.reviewedAt) {
      this.http.post<any>(`${this.CHEQUE_API}/${row.id}/acknowledge`, {
        reviewer: this.userName || 'admin'
      }).subscribe({
        next: () => { row.reviewedAt = new Date().toISOString(); },
        error: () => { /* not fatal: the row simply stays flagged */ }
      });
    }
  }

  decide(row: any, decision: 'ACCEPTED' | 'REJECTED'): void {
    this.http.post<any>(`${this.CHEQUE_API}/${row.id}/decision`, {
      decision,
      reviewer: this.userName || 'admin',
      note: 'Reviewed from the archive'
    }).subscribe({
      next: () => this.load(),
      error: () => { this.loadError = 'The decision could not be saved.'; }
    });
  }

  // ---------------------------------------------------------------- display

  statusClass(status: string): string {
    return status === 'ACCEPTED' ? 'badge-success'
         : status === 'REJECTED' ? 'badge-error' : 'badge-neutral';
  }

  rib(value: string): string {
    if (!value || value.length !== 20) { return value || '—'; }
    return `${value.slice(0, 2)} ${value.slice(2, 5)} ${value.slice(5, 18)} ${value.slice(18)}`;
  }

  money(value: any): string {
    const n = Number(value || 0);
    return n.toLocaleString('fr-TN', { maximumFractionDigits: 0 });
  }

  /** Image of the cheque as validated, when one was kept. */
  imageUrl(row: any): string | null {
    return row.imageName ? `${this.CHEQUE_API}/${row.id}/image` : null;
  }

  back(): void { this.router.navigate(['/admin/dashboard']); }
}

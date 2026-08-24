import { Component, NgZone, OnDestroy, OnInit } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';

interface TaskRow {
  task: any;
  attachments: any[];
  overdue: boolean;
}

/** The bank operation an agent is carrying out from a task. */
type OperationKind = 'none' | 'ACCOUNT_TYPE' | 'STATUS' | 'CONTACT' | 'CREDIT' | 'CHEQUEBOOK';

@Component({
  selector: 'app-schedule',
  templateUrl: './schedule.component.html',
  styleUrls: ['./schedule.component.css']
})
export class ScheduleComponent implements OnInit, OnDestroy {

  private readonly TASK_API = 'http://localhost:8087/api/tasks';
  private readonly CHAT_API = 'http://localhost:8087/api/chat';
  private readonly ATTACH_API = 'http://localhost:8087/api/attachments';
  private readonly CLIENT_API = 'http://localhost:8086/api/client';
  private readonly AUTH_API = 'http://localhost:8081/api/auth';

  readonly categories = [
    { id: 'CHEQUE_REVIEW', label: 'Cheque to verify', icon: '🧾' },
    { id: 'ACCOUNT_CHANGE', label: 'Account change', icon: '✏️' },
    { id: 'CREDIT_REQUEST', label: 'Credit request', icon: '🏦' },
    { id: 'DOCUMENT_CHECK', label: 'Documents to check', icon: '📎' },
    { id: 'OTHER', label: 'Other', icon: '•' }
  ];

  readonly columns = [
    { id: 'PENDING', label: 'To do', hint: 'Waiting to be picked up' },
    { id: 'IN_PROGRESS', label: 'In progress', hint: 'Being worked on now' },
    { id: 'DONE', label: 'Done', hint: 'Completed' }
  ];

  userId = '';
  userName = '';
  userRole = '';

  rows: TaskRow[] = [];
  counts: any = {};
  colleagues: any[] = [];

  scope: 'MINE' | 'ALL' = 'MINE';
  error = '';
  notice = '';

  // create panel
  showForm = false;
  saving = false;
  form: any = this.blankForm();
  clientQuery = '';
  clientMatches: any[] = [];
  pendingFiles: File[] = [];

  // detail drawer
  selected: TaskRow | null = null;

  /**
   * Panel width, cycled by the agent: normal → wide → almost full screen.
   * The choice is remembered, since it is a working preference rather than
   * something to re-pick on every task.
   */
  drawerWide = localStorage.getItem('auctus_drawer_wide') === 'true';
  drawerFull = localStorage.getItem('auctus_drawer_full') === 'true';

  /**
   * The banking operation the agent is carrying out for the open task. Each
   * category of work maps to the form that actually performs it, so the task is
   * not just ticked off - the client file really changes.
   */
  action: OperationKind = 'none';
  actionBusy = false;
  actionResult = '';
  actionError = '';

  /** The client file behind the open task, loaded when an action starts. */
  client: any = null;
  clientOps: any[] = [];

  typeForm: any = { accountType: 'COURANT', reason: '' };
  statusForm: any = { status: 'ACTIVE', reason: '' };
  contactForm: any = { phone: '', email: '', address: '', city: '' };
  creditForm: any = {
    type: 'CONSUMER', amount: null, durationMonths: 36,
    monthlyIncome: null, purpose: '', documents: {} as Record<string, boolean>
  };
  bookForm: any = { leafCount: 25, plafond: 5000, note: '' };

  /** Paperwork the branch requires, per product. */
  readonly creditDocuments: Record<string, string[]> = {
    CONSUMER: ['CIN copy', 'Salary certificate (under 90 days)', 'Last 3 payslips', 'Bank statements'],
    HOUSING: ['CIN copy', 'Salary certificate (under 90 days)', 'Last 3 payslips',
              'Property title', 'Building permit', 'Works estimate'],
    COMFORT_SAVINGS: ['CIN copy', 'Savings plan statement', 'Proof of income']
  };

  private stream: EventSource | null = null;

  constructor(private http: HttpClient, public router: Router,
              private route: ActivatedRoute, private zone: NgZone) {}

  ngOnInit(): void {
    const userStr = localStorage.getItem('user');
    if (userStr) {
      const user = JSON.parse(userStr);
      this.userId = user.id;
      this.userName = `${user.firstName} ${user.lastName}`;
      this.userRole = user.role;
    }
    // Admins land on the whole branch; agents on their own queue.
    this.scope = this.isAdmin ? 'ALL' : 'MINE';
    if (this.route.snapshot.queryParamMap.get('status') === 'PENDING') {
      this.scope = 'MINE';
    }

    this.loadColleagues();
    this.load();
    this.connect();
  }

  ngOnDestroy(): void {
    this.stream?.close();
  }

  get isAdmin(): boolean { return this.userRole === 'ADMIN'; }

  /** Live updates: a task assigned to you appears without a refresh. */
  private connect(): void {
    if (!this.userId) { return; }
    const source = new EventSource(`${this.CHAT_API}/stream?userId=${encodeURIComponent(this.userId)}`);
    this.stream = source;
    source.addEventListener('task', (event: MessageEvent) => {
      const payload = JSON.parse(event.data);
      this.zone.run(() => {
        this.load();
        if (payload.reason === 'assigned') {
          this.notice = `New task assigned to you: ${payload.title}`;
          setTimeout(() => (this.notice = ''), 6000);
        }
      });
    });
    source.onerror = () => { source.close(); setTimeout(() => this.connect(), 5000); };
  }

  loadColleagues(): void {
    this.http.get<any>(`${this.AUTH_API}/users`).subscribe({
      next: data => { this.colleagues = (data.users || []).filter((u: any) => u.status === 'ACTIVE'); },
      error: () => { this.colleagues = []; }
    });
  }

  load(): void {
    const params: any = {};
    if (this.scope === 'MINE') { params.assignedTo = this.userId; }
    this.http.get<any>(this.TASK_API, { params }).subscribe({
      next: data => {
        this.rows = data.items || [];
        this.counts = data.counts || {};
        if (this.selected) {
          this.selected = this.rows.find(r => r.task.id === this.selected!.task.id) || null;
        }
        this.openRequestedTask();
      },
      error: () => { this.error = 'Task service unreachable (port 8087).'; }
    });
  }

  /**
   * Arriving from a client file with ?task=… opens that task straight away, so an
   * agent can act on it without hunting through the board.
   */
  private openRequestedTask(): void {
    const wanted = this.route.snapshot.queryParamMap.get('task');
    if (!wanted || this.selected) { return; }

    let row = this.rows.find(r => r.task.id === wanted);
    if (row) {
      this.selected = row;
      return;
    }
    // It may belong to a colleague, which the "assigned to me" view hides.
    if (this.scope === 'MINE') {
      this.scope = 'ALL';
      this.load();
    }
  }

  column(status: string): TaskRow[] {
    return this.rows.filter(r => r.task.status === status);
  }

  // ------------------------------------------------------------ create

  private blankForm(): any {
    return {
      title: '', description: '', category: 'CHEQUE_REVIEW',
      assignedToId: '', assignedToName: '',
      clientRib: '', clientName: '',
      startsAt: '', dueAt: '', priority: 'NORMAL'
    };
  }

  openForm(): void {
    this.showForm = true;
    this.form = this.blankForm();
    this.pendingFiles = [];
    this.clientQuery = '';
    this.clientMatches = [];
    this.error = '';
  }

  searchClient(): void {
    const term = this.clientQuery.trim();
    if (!term) { return; }
    this.http.get<any>(`${this.CLIENT_API}/search`, { params: { q: term } }).subscribe({
      next: data => { this.clientMatches = (data.results || []).slice(0, 6); },
      error: () => { this.clientMatches = []; }
    });
  }

  pickClient(client: any): void {
    this.form.clientRib = client.rib;
    this.form.clientName = client.fullName;
    this.clientMatches = [];
    this.clientQuery = client.fullName;
  }

  onFiles(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files) { this.pendingFiles = Array.from(input.files); }
  }

  pickAssignee(user: any): void {
    this.form.assignedToId = user.id;
    this.form.assignedToName = `${user.firstName} ${user.lastName}`;
  }

  save(): void {
    if (!this.form.title.trim()) { this.error = 'Give the task a name.'; return; }
    if (!this.form.assignedToId) { this.error = 'Choose who should handle it.'; return; }

    this.saving = true;
    this.error = '';
    this.http.post<any>(this.TASK_API, {
      ...this.form,
      createdById: this.userId,
      createdByName: this.userName,
      createdByRole: this.userRole
    }).subscribe({
      next: task => {
        // Files are uploaded once the task exists and has an id to hang them on.
        if (this.pendingFiles.length === 0) {
          this.finishSave();
          return;
        }
        let remaining = this.pendingFiles.length;
        this.pendingFiles.forEach(file => {
          const data = new FormData();
          data.append('file', file);
          data.append('uploaderId', this.userId);
          data.append('uploaderName', this.userName);
          this.http.post(`${this.TASK_API}/${task.id}/attachments`, data).subscribe({
            next: () => { if (--remaining === 0) { this.finishSave(); } },
            error: () => { if (--remaining === 0) { this.finishSave(); } }
          });
        });
      },
      error: err => {
        this.error = err?.error?.error || 'The task could not be created.';
        this.saving = false;
      }
    });
  }

  private finishSave(): void {
    this.saving = false;
    this.showForm = false;
    this.notice = 'Task created and the assignee has been notified.';
    setTimeout(() => (this.notice = ''), 5000);
    this.load();
  }

  // ------------------------------------------------------------ actions

  move(row: TaskRow, status: string): void {
    this.http.patch<any>(`${this.TASK_API}/${row.task.id}`, { status }).subscribe({
      next: () => this.load(),
      error: () => { this.error = 'Could not update the task.'; }
    });
  }

  reassign(row: TaskRow, user: any): void {
    this.http.patch<any>(`${this.TASK_API}/${row.task.id}`, {
      assignedToId: user.id,
      assignedToName: `${user.firstName} ${user.lastName}`
    }).subscribe({ next: () => this.load(), error: () => { this.error = 'Reassignment failed.'; } });
  }

  uploadTo(row: TaskRow, event: Event): void {
    const input = event.target as HTMLInputElement;
    if (!input.files || input.files.length === 0) { return; }
    const data = new FormData();
    data.append('file', input.files[0]);
    data.append('uploaderId', this.userId);
    data.append('uploaderName', this.userName);
    this.http.post(`${this.TASK_API}/${row.task.id}/attachments`, data).subscribe({
      next: () => { input.value = ''; this.load(); },
      error: () => { this.error = 'The file could not be uploaded.'; }
    });
  }

  /** Normal → wide → near-full → back to normal. */
  cycleWidth(): void {
    if (!this.drawerWide && !this.drawerFull) {
      this.drawerWide = true;
    } else if (this.drawerWide && !this.drawerFull) {
      this.drawerFull = true;
    } else {
      this.drawerWide = false;
      this.drawerFull = false;
    }
    localStorage.setItem('auctus_drawer_wide', String(this.drawerWide));
    localStorage.setItem('auctus_drawer_full', String(this.drawerFull));
  }

  // ------------------------------------------------------------ bank operations

  /** Opens an operation form and pulls the client file it will act on. */
  startAction(kind: OperationKind): void {
    this.action = kind;
    this.actionResult = '';
    this.actionError = '';
    this.client = null;

    const rib = this.selected?.task?.clientRib;
    if (!rib) {
      this.actionError = 'This task has no client attached — add one first.';
      return;
    }

    this.http.get<any>(`${this.CLIENT_API}/rib/${rib}`).subscribe({
      next: client => {
        this.client = client;
        this.contactForm = {
          phone: client.phone, email: client.email,
          address: client.address, city: client.city
        };
        this.typeForm.accountType = client.accountType;
        this.statusForm.status = client.status;
        this.loadOperations();
      },
      error: () => { this.actionError = 'That client could not be loaded.'; }
    });
  }

  private loadOperations(): void {
    if (!this.client) { return; }
    this.http.get<any>(`${this.CLIENT_API}/${this.client.clientId}/operations`).subscribe({
      next: data => { this.clientOps = data.operations || []; },
      error: () => { this.clientOps = []; }
    });
  }

  /** True when the holder is a minor who has now reached majority. */
  get canConvertMinor(): boolean {
    return !!this.client?.minor && (this.client?.age ?? 0) >= 18;
  }

  get minorBlockedReason(): string {
    if (!this.client?.minor) { return ''; }
    return this.client.age >= 18
      ? ''
      : `The holder is ${this.client.age} — conversion is only possible from 18.`;
  }

  private context(): any {
    return {
      taskId: this.selected?.task?.id,
      performedById: this.userId,
      performedByName: this.userName
    };
  }

  private done(message: string): void {
    this.actionBusy = false;
    this.actionResult = message;
    this.loadOperations();
    this.load();
  }

  private failed(err: any, fallback: string): void {
    this.actionBusy = false;
    this.actionError = err?.error?.error || fallback;
  }

  applyAccountType(): void {
    this.actionBusy = true; this.actionError = '';
    this.http.patch<any>(`${this.CLIENT_API}/${this.client.clientId}/account-type`,
      { accountType: this.typeForm.accountType, reason: this.typeForm.reason, ...this.context() })
      .subscribe({
        next: res => {
          this.client = res.client;
          this.done(res.convertedFromMinor
            ? 'Account converted from a minor account — the guardian has been removed.'
            : `Account type changed to ${res.client.accountType}.`);
        },
        error: err => this.failed(err, 'The account type could not be changed.')
      });
  }

  applyStatus(): void {
    this.actionBusy = true; this.actionError = '';
    this.http.patch<any>(`${this.CLIENT_API}/${this.client.clientId}/status`,
      { status: this.statusForm.status, reason: this.statusForm.reason, ...this.context() })
      .subscribe({
        next: client => { this.client = client; this.done(`Account is now ${client.status}.`); },
        error: err => this.failed(err, 'The status could not be changed.')
      });
  }

  applyContact(): void {
    this.actionBusy = true; this.actionError = '';
    this.http.patch<any>(`${this.CLIENT_API}/${this.client.clientId}/contact`,
      { ...this.contactForm, ...this.context() })
      .subscribe({
        next: client => { this.client = client; this.done('Contact details updated.'); },
        error: err => this.failed(err, 'The details could not be saved.')
      });
  }

  get requiredDocuments(): string[] {
    return this.creditDocuments[this.creditForm.type] || [];
  }

  get missingDocuments(): string[] {
    return this.requiredDocuments.filter(doc => !this.creditForm.documents[doc]);
  }

  submitCredit(): void {
    this.actionBusy = true; this.actionError = '';
    const provided = this.requiredDocuments.filter(d => this.creditForm.documents[d]).join(', ');
    this.http.post<any>(`${this.CLIENT_API}/${this.client.clientId}/credits`, {
      type: this.creditForm.type,
      amount: this.creditForm.amount,
      durationMonths: this.creditForm.durationMonths,
      monthlyIncome: this.creditForm.monthlyIncome,
      purpose: this.creditForm.purpose,
      documentsProvided: provided,
      ...this.context()
    }).subscribe({
      next: res => this.done(`Credit file opened. ${res.note}`),
      error: err => this.failed(err, 'The credit file could not be opened.')
    });
  }

  submitChequeBook(): void {
    this.actionBusy = true; this.actionError = '';
    this.http.post<any>(`${this.CLIENT_API}/${this.client.clientId}/chequebooks`, {
      leafCount: this.bookForm.leafCount,
      plafond: this.bookForm.plafond,
      note: this.bookForm.note,
      ...this.context()
    }).subscribe({
      next: book => this.done(`Chequebook ordered: ${book.leafCount} leaves, ceiling ${book.plafond} DT.`),
      error: err => this.failed(err, 'The chequebook could not be ordered.')
    });
  }

  /** Closes the operation and marks the task finished in one step. */
  completeTask(): void {
    if (!this.selected) { return; }
    this.http.patch<any>(`${this.TASK_API}/${this.selected.task.id}`,
      { status: 'DONE', resolutionNote: this.actionResult })
      .subscribe({ next: () => { this.action = 'none'; this.load(); } });
  }

  // ------------------------------------------------------------ view helpers

  fileUrl(attachment: any): string { return `${this.ATTACH_API}/${attachment.id}`; }

  categoryOf(id: string): any {
    return this.categories.find(c => c.id === id) || this.categories[4];
  }

  priorityClass(priority: string): string {
    switch ((priority || '').toUpperCase()) {
      case 'URGENT': return 'p-urgent';
      case 'HIGH': return 'p-high';
      case 'LOW': return 'p-low';
      default: return 'p-normal';
    }
  }

  dueLabel(row: TaskRow): string {
    const due = row.task.dueAt;
    if (!due) { return 'No deadline'; }
    const diff = new Date(due).getTime() - Date.now();
    const hours = Math.round(diff / 3600000);
    if (diff < 0) { return `Overdue by ${Math.abs(hours)} h`; }
    if (hours < 24) { return `Due in ${hours} h`; }
    return `Due ${new Date(due).toLocaleDateString('en-GB', { day: '2-digit', month: 'short' })}`;
  }

  openClient(row: TaskRow): void {
    this.router.navigate([this.isAdmin ? '/admin/clients' : '/agent/clients'],
      { queryParams: { q: row.task.clientRib } });
  }

  goBack(): void {
    this.router.navigate([this.isAdmin ? '/admin/dashboard' : '/agent/dashboard']);
  }
}

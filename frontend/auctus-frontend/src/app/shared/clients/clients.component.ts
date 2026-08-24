import { Component, OnInit } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { ActivatedRoute, Router } from '@angular/router';

interface Client {
  clientId: string;
  fullName: string;
  firstName: string;
  lastName: string;
  cin: string;
  birthDate: string;
  age: number;
  minor: boolean;
  guardianName?: string;
  guardianCin?: string;
  city: string;
  address: string;
  phone: string;
  email: string;
  rib: string;
  accountNumber: string;
  accountType: string;
  agencyName: string;
  agencyAddress: string;
  balance: number;
  signatureDossier: string;
  signatureImage: string;
  status: string;
}

@Component({
  selector: 'app-clients',
  templateUrl: './clients.component.html',
  styleUrls: ['./clients.component.css']
})
export class ClientsComponent implements OnInit {

  private readonly CLIENT_API = 'http://localhost:8086/api/client';
  private readonly CHEQUE_API = 'http://localhost:8082/api/cheque';
  private readonly TASK_API = 'http://localhost:8087/api/tasks';

  userName = '';
  userId = '';
  userEmail = '';

  searchTerm = '';
  results: Client[] = [];
  selected: Client | null = null;

  isSearching = false;
  hasSearched = false;
  errorMessage = '';

  /** Signature specimen of the selected client, as a blob URL. */
  signatureUrl: string | null = null;

  /** Cheques already validated against the selected client's account. */
  clientCheques: any[] = [];
  chequesLoading = false;

  /**
   * Work outstanding on this client, and everything already done to the file.
   * An agent who opens a client should see what is pending on them without
   * having to go looking in the queue.
   */
  clientTasks: any[] = [];
  clientOps: any[] = [];

  /** Ad-hoc "can this account cover X?" check. */
  fundsAmount: number | null = null;
  fundsResult: any = null;
  fundsChecking = false;

  constructor(private http: HttpClient, public router: Router,
              private route: ActivatedRoute) {}

  ngOnInit(): void {
    const userStr = localStorage.getItem('user');
    if (userStr) {
      const user = JSON.parse(userStr);
      this.userName = `${user.firstName} ${user.lastName}`;
      this.userId = user.id;
      this.userEmail = user.email;
    }

    // Arriving from a task ("open client file") carries the RIB in the URL, so
    // the file opens straight away instead of making the agent search again.
    const term = this.route.snapshot.queryParamMap.get('q');
    if (term) {
      this.searchTerm = term;
      this.search();
    }
  }

  getInitials(): string {
    return this.userName
      .split(' ')
      .filter(part => part.length > 0)
      .map(part => part[0])
      .join('')
      .toUpperCase()
      .slice(0, 2);
  }

  search(): void {
    const term = this.searchTerm.trim();
    if (!term) {
      return;
    }

    this.isSearching = true;
    this.hasSearched = true;
    this.errorMessage = '';
    this.clearSelection();

    this.http.get<any>(`${this.CLIENT_API}/search`, { params: { q: term } }).subscribe({
      next: response => {
        this.results = response.results || [];
        this.isSearching = false;
        // A RIB or account number identifies exactly one client - open it directly.
        if (this.results.length === 1) {
          this.select(this.results[0]);
        }
      },
      error: () => {
        this.results = [];
        this.isSearching = false;
        this.errorMessage = 'Service client injoignable. Vérifiez qu\'il tourne sur le port 8086.';
      }
    });
  }

  select(client: Client): void {
    this.selected = client;
    this.fundsResult = null;
    this.fundsAmount = null;
    this.loadSignature(client);
    this.loadCheques(client);
    this.loadTasks(client);
    this.loadOperations(client);
  }

  private loadTasks(client: Client): void {
    this.http.get<any>(`${this.TASK_API}`, { params: { clientRib: client.rib } }).subscribe({
      next: data => {
        this.clientTasks = (data.items || [])
          .filter((row: any) => row.task.status !== 'CANCELLED');
      },
      error: () => { this.clientTasks = []; }
    });
  }

  private loadOperations(client: Client): void {
    this.http.get<any>(`${this.CLIENT_API}/${client.clientId}/operations`).subscribe({
      next: data => { this.clientOps = data.operations || []; },
      error: () => { this.clientOps = []; }
    });
  }

  /** Opens the task in the work queue, where the operation can be carried out. */
  openTask(row: any): void {
    const base = this.isAdmin ? '/admin/schedule' : '/agent/schedule';
    this.router.navigate([base], { queryParams: { task: row.task.id } });
  }

  get isAdmin(): boolean {
    const userStr = localStorage.getItem('user');
    return userStr ? JSON.parse(userStr).role === 'ADMIN' : false;
  }

  taskStatusClass(status: string): string {
    switch ((status || '').toUpperCase()) {
      case 'DONE': return 'badge-success';
      case 'IN_PROGRESS': return 'badge-warning';
      default: return 'badge-neutral';
    }
  }

  clearSelection(): void {
    this.selected = null;
    this.clientCheques = [];
    this.fundsResult = null;
    if (this.signatureUrl) {
      URL.revokeObjectURL(this.signatureUrl);
      this.signatureUrl = null;
    }
  }

  clearSearch(): void {
    this.searchTerm = '';
    this.results = [];
    this.hasSearched = false;
    this.errorMessage = '';
    this.clearSelection();
  }

  private loadSignature(client: Client): void {
    if (this.signatureUrl) {
      URL.revokeObjectURL(this.signatureUrl);
      this.signatureUrl = null;
    }
    this.http.get(`${this.CLIENT_API}/${client.clientId}/signature`, { responseType: 'blob' })
      .subscribe({
        next: blob => { this.signatureUrl = URL.createObjectURL(blob); },
        error: () => { this.signatureUrl = null; }
      });
  }

  private loadCheques(client: Client): void {
    this.chequesLoading = true;
    this.http.get<any>(`${this.CHEQUE_API}/by-rib/${client.rib}`).subscribe({
      next: response => {
        this.clientCheques = response.cheques || [];
        this.chequesLoading = false;
      },
      error: () => {
        this.clientCheques = [];
        this.chequesLoading = false;
      }
    });
  }

  checkFunds(): void {
    if (!this.selected || this.fundsAmount === null || this.fundsAmount <= 0) {
      return;
    }
    this.fundsChecking = true;
    this.http.get<any>(`${this.CLIENT_API}/funds`, {
      params: { rib: this.selected.rib, amount: String(this.fundsAmount) }
    }).subscribe({
      next: response => { this.fundsResult = response; this.fundsChecking = false; },
      error: () => { this.fundsResult = null; this.fundsChecking = false; }
    });
  }

  formatRib(rib: string): string {
    if (!rib || rib.length !== 20) {
      return rib;
    }
    // Bank(2) Agency(3) Account(13) Key(2), as printed on the cheque.
    return `${rib.slice(0, 2)} ${rib.slice(2, 5)} ${rib.slice(5, 18)} ${rib.slice(18)}`;
  }

  formatMoney(value: number | null | undefined): string {
    if (value === null || value === undefined) {
      return '-';
    }
    return Number(value).toLocaleString('fr-TN', {
      minimumFractionDigits: 3,
      maximumFractionDigits: 3
    });
  }

  statusClass(status: string): string {
    switch ((status || '').toUpperCase()) {
      case 'ACCEPTED': return 'badge-success';
      case 'REJECTED': return 'badge-error';
      case 'REVIEW': return 'badge-warning';
      default: return 'badge-neutral';
    }
  }

  goToDashboard(): void {
    this.router.navigate(['/agent/dashboard']);
  }
}

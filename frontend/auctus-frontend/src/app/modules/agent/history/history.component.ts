import { Component, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { HttpClient } from '@angular/common/http';

@Component({
  selector: 'app-history',
  templateUrl: './history.component.html',
  styleUrls: ['./history.component.css']
})
export class HistoryComponent implements OnInit {
  userName = '';
  userId = '';
  userEmail = '';
  
  allCheques: any[] = [];
  filteredCheques: any[] = [];
  displayedCheques: any[] = [];
  
  currentPage = 1;
  itemsPerPage = 10;
  itemsPerPageOptions = [10, 25, 50, 100];
  totalPages = 1;
  totalItems = 0;
  
  searchTerm = '';
  statusFilter = 'ALL';
  dateFrom = '';
  dateTo = '';
  minAmount: number | null = null;
  maxAmount: number | null = null;
  
  sortField = 'timestamp';
  sortDirection: 'asc' | 'desc' = 'desc';
  
  selectedCheque: any = null;
  showModal = false;
  modalTab: 'details' | 'qr' | 'rejection' = 'details';
  
  stats = {
    total: 0,
    validated: 0,
    rejected: 0,
    totalAmount: 0,
    todayCount: 0,
    weekCount: 0,
    monthCount: 0
  };
  
  isLoading = true;
  viewMode: 'table' | 'grid' = 'table';

  private readonly API_URL = 'http://localhost:8082';

  constructor(public router: Router, private http: HttpClient) {}

  ngOnInit(): void {
    this.loadUserData();
    this.loadHistory();
  }

  loadUserData(): void {
    const userStr = localStorage.getItem('user');
    if (userStr) {
      const user = JSON.parse(userStr);
      this.userName = `${user.firstName} ${user.lastName}`;
      this.userId = user.id;
      this.userEmail = user.email;
    }
  }

  getAgentStorageKey(): string {
    return `agent_${this.userId}_${this.userEmail}`;
  }

 /**
  * Reads the agent's validations back from the database.
  *
  * The history used to live in localStorage, which meant it vanished when the
  * cache was cleared and never followed the agent to another machine.
  */
 loadHistory(): void {
  this.isLoading = true;

  this.http.get<any>(`${this.API_URL}/api/cheque/history`, {
    params: { agentId: this.userId, size: '500' }
  }).subscribe({
    next: page => {
      this.allCheques = (page.content || []).map((c: any) => ({
        id: c.id,
        chequeNumber: c.chequeNumber || 'N/A',
        titulaire: c.issuerName || 'N/A',
        ribTitulaire: c.issuerRib || 'N/A',
        receiverRib: c.beneficiaryRib || 'N/A',
        amount: c.plafond ?? c.amount ?? 0,
        amountWritten: c.amountWritten,
        expiryDate: c.expiryDate,
        status: c.status,
        rejectionReason: c.rejectionReason,
        signatureScore: c.signatureScore,
        processingTime: c.processingTime,
        validatedBy: c.validatedBy,
        timestamp: c.validatedAt || c.createdAt,
        validationDate: c.validatedAt ? new Date(c.validatedAt).toLocaleDateString('fr-TN') : '',
        validationTime: c.validatedAt ? new Date(c.validatedAt).toLocaleTimeString('fr-TN') : ''
      }));
      this.applyFilters();
      this.calculateStats();
      this.isLoading = false;
    },
    error: () => {
      this.allCheques = [];
      this.applyFilters();
      this.calculateStats();
      this.isLoading = false;
    }
  });
}

  applyFilters(): void {
    let filtered = [...this.allCheques];
    
    if (this.searchTerm) {
      const term = this.searchTerm.toLowerCase();
      filtered = filtered.filter(cheque =>
        cheque.chequeNumber?.toLowerCase().includes(term) ||
        cheque.titulaire?.toLowerCase().includes(term) ||
        (cheque.rejectionReason?.toLowerCase().includes(term))
      );
    }
    
    if (this.statusFilter !== 'ALL') {
      filtered = filtered.filter(cheque => cheque.status === this.statusFilter);
    }
    
    if (this.dateFrom) {
      const fromDate = new Date(this.dateFrom);
      filtered = filtered.filter(cheque => new Date(cheque.timestamp) >= fromDate);
    }
    
    if (this.dateTo) {
      const toDate = new Date(this.dateTo);
      toDate.setHours(23, 59, 59);
      filtered = filtered.filter(cheque => new Date(cheque.timestamp) <= toDate);
    }
    
    if (this.minAmount !== null && !isNaN(this.minAmount)) {
      filtered = filtered.filter(cheque => cheque.amount >= this.minAmount!);
    }
    
    if (this.maxAmount !== null && !isNaN(this.maxAmount)) {
      filtered = filtered.filter(cheque => cheque.amount <= this.maxAmount!);
    }
    
    filtered.sort((a, b) => {
      let aVal = a[this.sortField];
      let bVal = b[this.sortField];
      if (this.sortField === 'amount') {
        aVal = Number(aVal);
        bVal = Number(bVal);
      } else if (this.sortField === 'timestamp') {
        aVal = new Date(a.timestamp).getTime();
        bVal = new Date(b.timestamp).getTime();
      }
      if (aVal < bVal) return this.sortDirection === 'asc' ? -1 : 1;
      if (aVal > bVal) return this.sortDirection === 'asc' ? 1 : -1;
      return 0;
    });
    
    this.filteredCheques = filtered;
    this.totalItems = filtered.length;
    this.totalPages = Math.ceil(this.totalItems / this.itemsPerPage);
    this.updatePagination();
  }

  /**
   * Slices the filtered list down to the current page.
   *
   * This used to map over the whole of `filteredCheques` without ever applying
   * `start`/`end`, so every row was rendered on every page and the pager below
   * the table changed nothing.
   */
  updatePagination(): void {
    const start = (this.currentPage - 1) * this.itemsPerPage;
    const end = start + Number(this.itemsPerPage);
    this.displayedCheques = this.filteredCheques.slice(start, end).map(cheque => ({
      ...cheque,
      titulaire: cheque.titulaire || cheque.qrData?.titulaire || 'N/A',
      chequeNumber: cheque.chequeNumber || cheque.qrData?.chequeNumber || 'N/A',
      amount: cheque.amount || cheque.qrData?.maxAmount || 0
    }));
  }

  calculateStats(): void {
    const validated = this.allCheques.filter(c => this.isAccepted(c.status));
    const rejected = this.allCheques.filter(c => this.isRejected(c.status));
    const totalAmount = validated.reduce((sum, c) => sum + (c.amount || 0), 0);
    
    const now = new Date();
    const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    const weekAgo = new Date(today);
    weekAgo.setDate(today.getDate() - 7);
    const monthAgo = new Date(today);
    monthAgo.setMonth(today.getMonth() - 1);
    
    this.stats = {
      total: this.allCheques.length,
      validated: validated.length,
      rejected: rejected.length,
      totalAmount: totalAmount,
      todayCount: this.allCheques.filter(c => new Date(c.timestamp) >= today).length,
      weekCount: this.allCheques.filter(c => new Date(c.timestamp) >= weekAgo).length,
      monthCount: this.allCheques.filter(c => new Date(c.timestamp) >= monthAgo).length
    };
  }

  onSearch(): void { this.currentPage = 1; this.applyFilters(); }
  clearFilters(): void {
    this.searchTerm = '';
    this.statusFilter = 'ALL';
    this.dateFrom = '';
    this.dateTo = '';
    this.minAmount = null;
    this.maxAmount = null;
    this.currentPage = 1;
    this.applyFilters();
  }

  onSort(field: string): void {
    if (this.sortField === field) {
      this.sortDirection = this.sortDirection === 'asc' ? 'desc' : 'asc';
    } else {
      this.sortField = field;
      this.sortDirection = 'desc';
    }
    this.applyFilters();
  }

  changePage(page: number): void { this.currentPage = page; this.updatePagination(); }

  /** The page size drives totalPages, so the pager has to be recomputed too. */
  changeItemsPerPage(): void {
    this.currentPage = 1;
    this.itemsPerPage = Number(this.itemsPerPage);
    this.totalPages = Math.ceil(this.totalItems / this.itemsPerPage) || 1;
    this.updatePagination();
  }

  getPages(): number[] {
    const pages = [];
    const maxVisible = 5;
    let start = Math.max(1, this.currentPage - Math.floor(maxVisible / 2));
    let end = Math.min(this.totalPages, start + maxVisible - 1);
    if (end - start + 1 < maxVisible) start = Math.max(1, end - maxVisible + 1);
    for (let i = start; i <= end; i++) pages.push(i);
    return pages;
  }

  viewDetails(cheque: any): void { this.selectedCheque = cheque; this.showModal = true; this.modalTab = 'details'; }
  closeModal(): void { this.showModal = false; this.selectedCheque = null; }

  /* -------------------------------------------------------------- status --
   * The service persists ACCEPTED / REVIEW / REJECTED. This page was written
   * against 'valid' / 'invalid', which no record has ever matched, so the two
   * counters read zero and every row - accepted ones included - was labelled
   * "Rejected". These helpers normalise the value once and everything else
   * asks them rather than comparing strings inline.
   */
  private norm(status: string): string { return (status || '').toUpperCase(); }

  isAccepted(status: string): boolean { return this.norm(status) === 'ACCEPTED'; }
  isRejected(status: string): boolean { return this.norm(status) === 'REJECTED'; }
  isReview(status: string): boolean { return this.norm(status) === 'REVIEW'; }

  /** Maps onto the shared badge classes in styles.css. */
  getStatusClass(status: string): string {
    if (this.isAccepted(status)) { return 'au-badge-good'; }
    if (this.isRejected(status)) { return 'au-badge-bad'; }
    if (this.isReview(status)) { return 'au-badge-warn'; }
    return 'au-badge-neutral';
  }

  getStatusText(status: string): string {
    if (this.isAccepted(status)) { return 'Validated'; }
    if (this.isRejected(status)) { return 'Rejected'; }
    if (this.isReview(status)) { return 'Needs review'; }
    return status || '—';
  }

  formatAmount(amount: number): string {
    if (!amount) return '0.00 DT';
    return new Intl.NumberFormat('fr-TN', { style: 'currency', currency: 'TND', minimumFractionDigits: 2 }).format(amount);
  }

  formatDateTime(dateStr: string): string {
    if (!dateStr) return '-';
    try {
      const date = new Date(dateStr);
      return date.toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' });
    } catch { return dateStr; }
  }

  exportToCSV(): void {
    const headers = ['Date', 'Time', 'Cheque Number', 'Account Holder', 'Amount', 'Status', 'Rejection Reason', 'Validated By', 'Confidence Score'];
    const rows = this.filteredCheques.map(cheque => [
      this.formatDateTime(cheque.timestamp).split(' ')[0],
      this.formatDateTime(cheque.timestamp).split(' ')[1],
      cheque.chequeNumber,
      cheque.titulaire,
      cheque.amount,
      this.getStatusText(cheque.status),
      cheque.rejectionReason || '-',
      cheque.validatedBy,
      cheque.confidenceScore || '-'
    ]);
    
    // Quote every field. A rejection reason routinely contains a comma, which
    // silently shifted every column after it into the wrong header.
    const esc = (v: any) => `"${String(v ?? '').replace(/"/g, '""')}"`;
    const csvContent = [headers, ...rows]
      .map(row => row.map(esc).join(','))
      .join('\r\n');
    const blob = new Blob(["\uFEFF" + csvContent], { type: 'text/csv;charset=utf-8;' });
    const link = document.createElement('a');
    link.href = URL.createObjectURL(blob);
    link.download = `cheque_history_${this.userName}_${new Date().toISOString().split('T')[0]}.csv`;
    link.click();
    URL.revokeObjectURL(link.href);
  }

  goToDashboard(): void { this.router.navigate(['/agent/dashboard']); }
  getInitials(): string { return this.userName ? this.userName.split(' ').map(n => n[0]).join('').toUpperCase() : 'AG'; }
}
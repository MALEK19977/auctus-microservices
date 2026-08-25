import { Component, OnInit, OnDestroy } from '@angular/core';
import { Router } from '@angular/router';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { interval, Subscription } from 'rxjs';

@Component({
  selector: 'app-dashboard',
  templateUrl: './dashboard.component.html',
  styleUrls: ['./dashboard.component.css']
})
export class DashboardComponent implements OnInit, OnDestroy {
  userName = 'Agent';
  userId = '';
  userEmail = '';
  userRole = '';
  totalCheques = 0;
  pendingCheques = 0;
  rejectedCheques = 0;
  
  private localTotalCheques = 0;
  private localRejectedCheques = 0;
  
  totalTrend = '+0%';
  pendingTrend = '0%';
  rejectedTrend = '0%';
  totalTrendPositive = true;
  pendingTrendNeutral = true;
  rejectedTrendNegative = false;
  
  selectedImage: File | null = null;
  selectedImagePreview: string | null = null;
  isDragover = false;
  validationStarted = false;
  
  recentCheques: any[] = [];
  
  qrData: any = null;
  scanDate: Date = new Date();
  
  step1Completed = false;
  step1Active = false;
  step1Error = false;
  step1ErrorMessage = '';
  
  step2Completed = false;
  step2Active = false;
  step2Error = false;
  step2ErrorMessage = '';
  
  step3Completed = false;
  step3Active = false;
  step3Error = false;
  step3ErrorMessage = '';
  
  validationCompleted = false;
  finalResult: 'success' | 'error' | null = null;
  finalErrorMessage = '';

  detailedErrors: any = null;
  validationDetails: any = null;
  
  currentValidation: any = null;
  showValidationSummary = false;

  /** Tasks handed to this agent that are still open (assigned or in progress). */
  openTasks = 0;

  private refreshSubscription: Subscription | null = null;
  private readonly API_URL = 'http://localhost:8082';
  private readonly TASK_API = 'http://localhost:8087/api/tasks';

  constructor(public router: Router, private http: HttpClient) {}

  ngOnInit(): void {
    this.loadUserData();
    this.loadAgentData();
    this.loadRecentCheques();
    this.refreshSubscription = interval(5000).subscribe(() => {
      this.loadAgentData();
      this.loadRecentCheques();
    });
  }

  ngOnDestroy(): void {
    if (this.refreshSubscription) {
      this.refreshSubscription.unsubscribe();
    }
  }

  loadUserData(): void {
    const userStr = localStorage.getItem('user');
    if (userStr) {
      const user = JSON.parse(userStr);
      this.userName = `${user.firstName} ${user.lastName}`;
      this.userId = user.id;
      this.userEmail = user.email;
      this.userRole = user.role;
    }
  }

  getAgentStorageKey(): string {
    return `agent_${this.userId}_${this.userEmail}`;
  }

  /**
     * Counters come from the database, never from the browser. An agent who logs
     * out, clears their cache or moves to another machine still sees the exact
     * work they did.
     */
  loadAgentData(): void {
    if (!this.userId) {
      return;
    }
    this.http.get<any>(`${this.API_URL}/api/cheque/statistics`, {
      params: { agentId: this.userId }
    }).subscribe({
      next: stats => {
        this.totalCheques = stats.total || 0;
        // Pending is work waiting on this agent - the tasks handed to them - not
        // cheques. Every validated cheque used to land here and bump the count,
        // which was never something the agent had to act on.
        this.pendingCheques = this.openTasks;
        this.rejectedCheques = stats.rejected || 0;
        this.localTotalCheques = this.totalCheques;
        this.localRejectedCheques = this.rejectedCheques;
      },
      error: () => { /* keep the last known figures rather than blanking the tiles */ }
    });

    this.http.get<any>(`${this.TASK_API}/pending-count`, { params: { userId: this.userId } })
      .subscribe({
        next: data => { this.openTasks = data.open || 0; },
        error: () => { /* the collaboration service may simply not be running */ }
      });
  }

  /** The database is the record of truth; nothing is persisted client-side. */
  saveAgentData(): void {
    this.loadAgentData();
    this.loadRecentCheques();
  }

  loadRecentCheques(): void {
    if (!this.userId) {
      return;
    }
    this.http.get<any>(`${this.API_URL}/api/cheque/history`, {
      params: { agentId: this.userId, size: '10' }
    }).subscribe({
      next: page => {
        this.recentCheques = (page.content || []).map((c: any) => this.toRow(c));
      },
      error: () => { /* leave the current list in place on a transient failure */ }
    });
  }

  /** Maps a persisted cheque onto the shape the dashboard table renders. */
  private toRow(c: any): any {
    const validated = c.validatedAt ? new Date(c.validatedAt) : null;
    return {
      id: c.id,
      chequeNumber: c.chequeNumber || '—',
      titulaire: c.issuerName || c.beneficiaryName || '—',
      amount: c.plafond ?? c.amount ?? 0,
      status: c.status,
      date: validated ? validated.toLocaleDateString('fr-TN') : '',
      time: validated ? validated.toLocaleTimeString('fr-TN') : '',
      validationDate: validated ? validated.toLocaleDateString('fr-TN') : '',
      validationTime: validated ? validated.toLocaleTimeString('fr-TN') : ''
    };
  }

  getInitials(): string {
    if (this.userName && this.userName !== 'Agent') {
      return this.userName.split(' ').map(n => n[0]).join('').toUpperCase();
    }
    return 'AG';
  }

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.isDragover = true;
  }

  onDragLeave(event: DragEvent): void {
    event.preventDefault();
    this.isDragover = false;
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    this.isDragover = false;
    const files = event.dataTransfer?.files;
    if (files && files.length > 0) {
      this.processFile(files[0]);
    }
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (input.files && input.files.length > 0) {
      this.processFile(input.files[0]);
    }
  }

  processFile(file: File): void {
    if (!file.type.startsWith('image/')) {
      alert('Please select an image file only');
      return;
    }
    if (file.size > 5 * 1024 * 1024) {
      alert('Image size must not exceed 5MB');
      return;
    }
    this.selectedImage = file;
    const reader = new FileReader();
    reader.onload = () => {
      this.selectedImagePreview = reader.result as string;
    };
    reader.readAsDataURL(file);
    this.resetValidation();
    this.qrData = null;
    this.detailedErrors = null;
    this.validationDetails = null;
    this.showValidationSummary = false;
  }

  removeImage(event: Event): void {
    event.stopPropagation();
    this.selectedImage = null;
    this.selectedImagePreview = null;
    this.resetValidation();
    this.qrData = null;
    this.detailedErrors = null;
    this.validationDetails = null;
    this.showValidationSummary = false;
  }

  startValidation(): void {
    if (!this.selectedImage) {
      alert('Please select an image first');
      return;
    }
    this.validationStarted = true;
    this.resetSteps();
    this.step1Active = true;
    this.validateWithBackend();
  }

  validateWithBackend(): void {
    const formData = new FormData();
    formData.append('frontImage', this.selectedImage!);
    formData.append('agentId', this.userId);
    formData.append('agentName', this.userName);
    formData.append('agentEmail', this.userEmail);

    const token = localStorage.getItem('token');
    
    console.log('📤 Sending to Cheque Service...');
    
    this.http.post(`${this.API_URL}/api/cheque/validate`, formData, {
      headers: new HttpHeaders({ 'Authorization': `Bearer ${token}` })
    }).subscribe({
      next: (response: any) => {
        console.log('📥 Backend Response:', response);
        
        this.step1Active = false;
        this.step1Completed = true;
        
        // EXTRACT QR DATA FROM BACKEND RESPONSE
        if (response.qrData) {
          this.qrData = {
            chequeNumber: response.qrData.cheque_number || response.chequeNumber,
            maxAmount: response.qrData.max_amount || response.amount,
            expiryDate: response.qrData.expiry_date,
            titulaire: response.qrData.titulaire || response.beneficiary,
            ribTitulaire: response.qrData.rib_titulaire,
            receiverRib: response.qrData.receiver_rib,
            signatureScore: response.signatureScore || 95,
            confidenceScore: response.confidenceScore || 90,
            processingTime: response.processingTime
          };
          this.scanDate = new Date();
          console.log('✅ QR Data loaded:', this.qrData);
        }
        
        this.step2Active = true;
        setTimeout(() => {
          this.step2Active = false;
          this.step2Completed = true;
          
          this.step3Active = true;
          setTimeout(() => {
            this.step3Active = false;
            this.step3Completed = true;
            
            if (response.status === 'ACCEPTED') {
              this.validationSuccess();
            } else {
              this.step1Error = true;
              this.step1ErrorMessage = response.rejectionReason || 'Validation failed';
              this.validationFailed(response.rejectionReason || 'Cheque validation failed');
            }
          }, 800);
        }, 800);
      },
      error: (error) => {
        console.error('❌ Backend error:', error);
        this.step1Active = false;
        this.step1Error = true;
        this.step1ErrorMessage = 'Connection error';
        this.validationFailed('Connection error');
      }
    });
  }

  formatRib(rib: string): string {
    if (!rib || rib === 'N/A') return 'N/A';
    const cleanRib = rib.replace(/\s/g, '');
    if (cleanRib.length >= 20) {
      return `${cleanRib.substring(0,2)} ${cleanRib.substring(2,5)} ${cleanRib.substring(5,18)} ${cleanRib.substring(18,20)}`;
    }
    if (cleanRib.length === 19) {
      return `${cleanRib.substring(0,2)} ${cleanRib.substring(2,5)} ${cleanRib.substring(5,17)} ${cleanRib.substring(17,19)}`;
    }
    return rib;
  }

  getProcessingTimeWidth(processingTime: number): number {
    const maxTime = 3;
    const percentage = Math.min(100, ((maxTime - processingTime) / maxTime) * 100);
    return Math.max(0, percentage);
  }

  validationSuccess(): void {
    this.validationCompleted = true;
    this.finalResult = 'success';
    this.localTotalCheques++;
    this.totalCheques = this.localTotalCheques;
    
    const now = new Date();
    const validationRecord = {
      id: Date.now(),
      chequeNumber: this.qrData?.chequeNumber,
      titulaire: this.qrData?.titulaire,
      amount: this.qrData?.maxAmount,
      sender: this.userName,
      receiver: this.qrData?.titulaire,
      validationDate: now.toLocaleDateString(),
      validationTime: now.toLocaleTimeString(),
      timestamp: now.toISOString(),
      status: 'valid',
      validatedBy: this.userName,
      ribTitulaire: this.qrData?.ribTitulaire,
      receiverRib: this.qrData?.receiverRib,
      expiryDate: this.qrData?.expiryDate,
      qrData: this.qrData
    };
    
    this.currentValidation = validationRecord;
    this.showValidationSummary = true;
    // The cheque was already persisted by the backend; re-read rather than
    // appending a second, client-side copy of the same validation.
    this.saveAgentData();
  }

  validationFailed(message: string): void {
    this.validationCompleted = true;
    this.finalResult = 'error';
    this.finalErrorMessage = message;
    this.localRejectedCheques++;
    this.rejectedCheques = this.localRejectedCheques;
    
    const now = new Date();
    const validationRecord = {
      id: Date.now(),
      chequeNumber: this.qrData?.chequeNumber || 'REJECTED',
      titulaire: this.qrData?.titulaire || 'Unknown',
      amount: this.qrData?.maxAmount || 0,
      sender: this.userName,
      receiver: this.qrData?.titulaire || 'N/A',
      validationDate: now.toLocaleDateString(),
      validationTime: now.toLocaleTimeString(),
      timestamp: now.toISOString(),
      status: 'invalid',
      validatedBy: this.userName,
      rejectionReason: message,
      qrData: this.qrData
    };
    
    this.currentValidation = validationRecord;
    this.showValidationSummary = true;
    this.saveAgentData();
  }

  resetSteps(): void {
    this.step1Completed = false;
    this.step1Active = false;
    this.step1Error = false;
    this.step1ErrorMessage = '';
    this.step2Completed = false;
    this.step2Active = false;
    this.step2Error = false;
    this.step2ErrorMessage = '';
    this.step3Completed = false;
    this.step3Active = false;
    this.step3Error = false;
    this.step3ErrorMessage = '';
    this.validationCompleted = false;
    this.finalResult = null;
    this.finalErrorMessage = '';
    this.detailedErrors = null;
    this.validationDetails = null;
  }

  resetValidation(): void {
    this.validationStarted = false;
    this.resetSteps();
    this.showValidationSummary = false;
    this.currentValidation = null;
  }

  formatAmount(amount: number): string {
    if (!amount) return '0 DT';
    return amount.toLocaleString() + ' DT';
  }

  /**
   * Bars for the isometric "validated vs rejected" chart.
   *
   * Heights are a share of the tallest bar rather than of the total, so a quiet
   * day still produces a readable chart instead of three slivers. The floor of 1
   * on the divisor keeps a fresh account (all zeros) from dividing by zero.
   */
  get chartBars(): Array<{ label: string; value: number; pct: number; kind: string }> {
    const rows = [
      { label: 'Validated', value: this.totalCheques, kind: 'ok' },
      { label: 'Pending', value: this.pendingCheques + this.openTasks, kind: 'warn' },
      { label: 'Rejected', value: this.rejectedCheques, kind: 'bad' }
    ];
    const max = Math.max(1, ...rows.map(r => r.value));
    return rows.map(r => ({ ...r, pct: Math.round((r.value / max) * 100) }));
  }

  /** Share of all decided cheques that were accepted - the headline quality number. */
  get acceptanceRate(): number {
    const decided = this.totalCheques + this.rejectedCheques;
    return decided ? Math.round((this.totalCheques / decided) * 100) : 0;
  }

  /** Total work waiting on this agent, across cheques and delegated tasks. */
  get pendingTotal(): number {
    return this.pendingCheques + this.openTasks;
  }

  viewAllHistory(): void {
    this.router.navigate(['/agent/history']);
  }

  logout(): void {
    localStorage.clear();
    this.router.navigate(['/login']);
  }

  // History and Archive were the same page under two names; Archive is the one
  // that remains, and this still points there for any caller left over.
  goToHistory(): void { this.router.navigate(['/agent/archive']); }
  goToClients(): void { this.router.navigate(['/agent/clients']); }

  /**
   * The database stores ACCEPTED or REJECTED. The table used to compare against
   * 'valid', which no record ever matched, so every row read "Rejected".
   */
  statusLabel(status: string): string {
    switch ((status || '').toUpperCase()) {
      case 'ACCEPTED': return 'Validated';
      case 'REJECTED': return 'Rejected';
      default: return status || '—';
    }
  }

  /** The pending tile leads to the work actually waiting on this agent. */
  goToPendingWork(): void {
    this.router.navigate(['/agent/schedule'], { queryParams: { status: 'PENDING' } });
  }
  goToProfile(): void { this.router.navigate(['/agent/profile']); }
  goToSchedule(): void { this.router.navigate(['/agent/schedule']); }
  goToConversations(): void { this.router.navigate(['/agent/conversations']); }
  goToArchive(): void { this.router.navigate(['/agent/archive']); }
}
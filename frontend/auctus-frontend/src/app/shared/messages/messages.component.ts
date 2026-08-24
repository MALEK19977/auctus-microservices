import { Component, NgZone, OnDestroy, OnInit } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';

interface Member {
  userId: string;
  userName: string;
  userRole: string;
}

interface Conversation {
  id: string;
  type: 'DIRECT' | 'GROUP';
  name: string;
  memberCount: number;
  members: Member[];
  lastMessage: string;
  lastMessageSender: string;
  lastMessageAt: string;
  unread: number;
}

@Component({
  selector: 'app-messages',
  templateUrl: './messages.component.html',
  styleUrls: ['./messages.component.css']
})
export class MessagesComponent implements OnInit, OnDestroy {

  private readonly CHAT_API = 'http://localhost:8087/api/chat';
  private readonly AUTH_API = 'http://localhost:8081/api/auth';

  userId = '';
  userName = '';
  userRole = '';

  conversations: Conversation[] = [];
  contacts: any[] = [];
  messages: any[] = [];

  active: Conversation | null = null;
  draft = '';
  sending = false;
  error = '';

  /** Live connection state, shown honestly in the header. */
  connected = false;

  // composer panel
  panel: 'none' | 'direct' | 'group' = 'none';
  groupName = '';
  groupPicks: Record<string, boolean> = {};
  creating = false;

  showMembers = false;
  addPicks: Record<string, boolean> = {};

  private readonly ATTACH_API = 'http://localhost:8087/api/attachments';

  // voice notes
  recording = false;
  recordSeconds = 0;
  private recorder: MediaRecorder | null = null;
  private chunks: BlobPart[] = [];
  private recordTimer: any = null;

  uploading = false;

  private stream: EventSource | null = null;
  private reconnectTimer: any = null;
  private reconnectDelay = 1000;

  constructor(private http: HttpClient, public router: Router, private zone: NgZone) {}

  ngOnInit(): void {
    const userStr = localStorage.getItem('user');
    if (userStr) {
      const user = JSON.parse(userStr);
      this.userId = user.id;
      this.userName = `${user.firstName} ${user.lastName}`;
      this.userRole = user.role;
    }
    this.loadContacts();
    this.loadConversations();
    this.connect();
  }

  ngOnDestroy(): void {
    this.stream?.close();
    clearTimeout(this.reconnectTimer);
  }

  get isAdmin(): boolean { return this.userRole === 'ADMIN'; }

  // ------------------------------------------------------------ live stream

  /**
   * Opens the server-sent stream. The server writes the moment a message lands,
   * so nothing here polls. A dropped connection retries with a widening delay.
   */
  private connect(): void {
    if (!this.userId) { return; }
    this.stream?.close();

    const source = new EventSource(`${this.CHAT_API}/stream?userId=${encodeURIComponent(this.userId)}`);
    this.stream = source;

    source.addEventListener('connected', () => {
      // EventSource callbacks land outside Angular, so re-enter the zone to render.
      this.zone.run(() => {
        this.connected = true;
        this.reconnectDelay = 1000;
      });
    });

    source.addEventListener('message', (event: MessageEvent) => {
      const message = JSON.parse(event.data);
      this.zone.run(() => {
        if (this.active && message.conversationId === this.active.id) {
          this.messages = [...this.messages, message];
          setTimeout(() => this.scrollToLatest(), 30);
          if (message.senderId !== this.userId) { this.markRead(this.active.id); }
        }
        this.loadConversations();
      });
    });

    source.addEventListener('conversations', () => {
      this.zone.run(() => this.loadConversations());
    });

    source.onerror = () => {
      this.zone.run(() => { this.connected = false; });
      source.close();
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = setTimeout(() => this.connect(), this.reconnectDelay);
      this.reconnectDelay = Math.min(this.reconnectDelay * 2, 15000);
    };
  }

  // ------------------------------------------------------------ data

  loadContacts(): void {
    this.http.get<any>(`${this.AUTH_API}/users`).subscribe({
      next: data => {
        this.contacts = (data.users || [])
          .filter((u: any) => u.id !== this.userId && u.status === 'ACTIVE');
      },
      error: () => { this.contacts = []; }
    });
  }

  loadConversations(): void {
    if (!this.userId) { return; }
    this.http.get<any>(`${this.CHAT_API}/conversations`, { params: { userId: this.userId } })
      .subscribe({
        next: data => {
          this.conversations = data.conversations || [];
          if (this.active) {
            const refreshed = this.conversations.find(c => c.id === this.active!.id);
            if (refreshed) { this.active = refreshed; }
          }
        },
        error: () => { this.error = 'Chat service unreachable (port 8087).'; }
      });
  }

  open(conversation: Conversation): void {
    this.active = conversation;
    this.panel = 'none';
    this.showMembers = false;
    this.http.get<any>(`${this.CHAT_API}/conversations/${conversation.id}/messages`,
      { params: { userId: this.userId } }).subscribe({
      next: data => {
        this.messages = data.messages || [];
        setTimeout(() => this.scrollToLatest(), 40);
        this.loadConversations();
      },
      error: () => { this.error = 'Could not open the conversation.'; }
    });
  }

  private markRead(conversationId: string): void {
    this.http.get<any>(`${this.CHAT_API}/conversations/${conversationId}/messages`,
      { params: { userId: this.userId } }).subscribe({ next: () => this.loadConversations() });
  }

  send(): void {
    const body = this.draft.trim();
    if (!body || !this.active || this.sending) { return; }

    this.sending = true;
    this.http.post<any>(`${this.CHAT_API}/conversations/${this.active.id}/messages`, {
      senderId: this.userId,
      senderName: this.userName,
      body
    }).subscribe({
      next: () => {
        // The echo arrives on the stream; nothing is appended here to avoid a duplicate.
        this.draft = '';
        this.sending = false;
      },
      error: () => { this.error = 'The message could not be sent.'; this.sending = false; }
    });
  }

  // ------------------------------------------------------------ attachments

  attachmentUrl(message: any): string {
    return `${this.ATTACH_API}/${message.attachmentId}`;
  }

  /** Sends a picked photo or document into the open conversation. */
  onFilePicked(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (!input.files || input.files.length === 0 || !this.active) { return; }
    this.upload(input.files[0], input.files[0].name);
    input.value = '';
  }

  private upload(file: Blob, caption: string): void {
    if (!this.active) { return; }
    this.uploading = true;
    const data = new FormData();
    data.append('file', file, caption);
    data.append('senderId', this.userId);
    data.append('senderName', this.userName);
    data.append('caption', caption);

    this.http.post(`${this.CHAT_API}/conversations/${this.active.id}/attachments`, data)
      .subscribe({
        next: () => { this.uploading = false; },
        error: () => { this.error = 'The file could not be sent.'; this.uploading = false; }
      });
  }

  /**
   * Records a voice note with the browser's own recorder. Needs microphone
   * permission, which the browser asks for the first time.
   */
  async toggleRecording(): Promise<void> {
    if (this.recording) {
      this.recorder?.stop();
      return;
    }
    try {
      const media = await navigator.mediaDevices.getUserMedia({ audio: true });
      this.chunks = [];
      const recorder = new MediaRecorder(media);
      this.recorder = recorder;

      recorder.ondataavailable = event => { this.chunks.push(event.data); };
      recorder.onstop = () => {
        clearInterval(this.recordTimer);
        media.getTracks().forEach(track => track.stop());
        const blob = new Blob(this.chunks, { type: recorder.mimeType || 'audio/webm' });
        this.zone.run(() => {
          this.recording = false;
          if (blob.size > 0) {
            // No colons: Windows rejects them in file names and the upload fails.
            const stamp = new Date().toISOString().slice(11, 19).replace(/:/g, '-');
            this.upload(blob, `voice-note-${stamp}.webm`);
          } else {
            this.error = 'Nothing was recorded — check the microphone.';
          }
        });
      };

      recorder.start();
      this.zone.run(() => {
        this.recording = true;
        this.recordSeconds = 0;
      });
      this.recordTimer = setInterval(() => {
        this.zone.run(() => { this.recordSeconds++; });
        if (this.recordSeconds >= 120) { this.recorder?.stop(); }
      }, 1000);
    } catch {
      this.error = 'Microphone unavailable — check the browser permission.';
    }
  }

  get recordLabel(): string {
    const m = Math.floor(this.recordSeconds / 60);
    const s = this.recordSeconds % 60;
    return `${m}:${String(s).padStart(2, '0')}`;
  }

  // ------------------------------------------------------------ creating chats

  startDirect(contact: any): void {
    this.creating = true;
    this.http.post<any>(`${this.CHAT_API}/conversations`, {
      type: 'DIRECT',
      createdBy: this.userId,
      createdByName: this.userName,
      createdByRole: this.userRole,
      members: [{ userId: contact.id, userName: `${contact.firstName} ${contact.lastName}`, userRole: contact.role }]
    }).subscribe({
      next: result => {
        this.creating = false;
        this.panel = 'none';
        this.loadConversations();
        setTimeout(() => {
          const found = this.conversations.find(c => c.id === result.id);
          if (found) { this.open(found); }
        }, 300);
      },
      error: () => { this.error = 'Could not start the conversation.'; this.creating = false; }
    });
  }

  get pickedCount(): number {
    return Object.values(this.groupPicks).filter(Boolean).length;
  }

  /**
   * Reassigning the map (rather than mutating it) makes the change unmistakable
   * to change detection, so the counter and the button state always agree with
   * the ticked boxes.
   */
  togglePick(contactId: string, checked: boolean): void {
    this.groupPicks = { ...this.groupPicks, [contactId]: checked };
  }

  /** What still stops the group being created, in plain words. Empty when ready. */
  get groupBlocker(): string {
    if (!this.userId) { return 'You are not signed in.'; }
    if (this.contacts.length < 2) {
      return 'A group needs two other active accounts; only ' +
        this.contacts.length + ' is available.';
    }
    if (!this.groupName.trim()) { return 'Give the group a name.'; }
    if (this.pickedCount < 2) {
      return `Pick ${2 - this.pickedCount} more colleague(s) — a group is three people including you.`;
    }
    return '';
  }

  createGroup(): void {
    const members = this.contacts
      .filter(c => this.groupPicks[c.id])
      .map(c => ({ userId: c.id, userName: `${c.firstName} ${c.lastName}`, userRole: c.role }));

    if (!this.groupName.trim()) { this.error = 'Give the group a name.'; return; }
    if (members.length < 2) { this.error = 'Pick at least two people — a group needs three with you.'; return; }

    this.creating = true;
    this.error = '';
    this.http.post<any>(`${this.CHAT_API}/conversations`, {
      type: 'GROUP',
      name: this.groupName.trim(),
      createdBy: this.userId,
      createdByName: this.userName,
      createdByRole: this.userRole,
      members
    }).subscribe({
      next: result => {
        this.creating = false;
        this.panel = 'none';
        this.groupName = '';
        this.groupPicks = {};
        this.loadConversations();
        setTimeout(() => {
          const found = this.conversations.find(c => c.id === result.id);
          if (found) { this.open(found); }
        }, 300);
      },
      error: err => {
        // Surface what actually came back rather than a generic sentence, so a
        // rejected rule and an unreachable service are told apart.
        this.error = err?.error?.error
          || (err?.status === 0
                ? 'Chat service unreachable on port 8087 — is collab-service running?'
                : `The group could not be created (HTTP ${err?.status}).`);
        this.creating = false;
      }
    });
  }

  /** People not already in the open group. */
  get addableContacts(): any[] {
    if (!this.active) { return []; }
    const inRoom = new Set(this.active.members.map(m => m.userId));
    return this.contacts.filter(c => !inRoom.has(c.id));
  }

  addToGroup(contact: any): void {
    if (!this.active) { return; }
    this.http.post<any>(`${this.CHAT_API}/conversations/${this.active.id}/members`, {
      userId: contact.id,
      userName: `${contact.firstName} ${contact.lastName}`,
      userRole: contact.role
    }).subscribe({
      next: () => { this.loadConversations(); this.open(this.active!); },
      error: () => { this.error = 'Could not add that person.'; }
    });
  }

  removeFromGroup(member: Member): void {
    if (!this.active) { return; }
    this.http.delete<any>(`${this.CHAT_API}/conversations/${this.active.id}/members/${member.userId}`)
      .subscribe({
        next: () => { this.loadConversations(); this.open(this.active!); },
        error: () => { this.error = 'Could not remove that person.'; }
      });
  }

  // ------------------------------------------------------------ view helpers

  isMine(message: any): boolean { return message.senderId === this.userId; }
  isSystem(message: any): boolean { return message.kind === 'SYSTEM'; }

  /** Hide the name when the previous bubble came from the same person. */
  showSender(index: number): boolean {
    if (!this.active || this.active.type === 'DIRECT') { return false; }
    const current = this.messages[index];
    if (this.isMine(current) || this.isSystem(current)) { return false; }
    const previous = this.messages[index - 1];
    return !previous || previous.senderId !== current.senderId;
  }

  initials(name: string): string {
    return (name || '?').split(' ').filter(p => p).map(p => p[0]).join('').toUpperCase().slice(0, 2);
  }

  /** Stable per-person tint so avatars are distinguishable at a glance. */
  avatarTint(name: string): string {
    const palette = ['#0F1A3A', '#7C5B12', '#1F5F4E', '#5B2E6B', '#0E4C6B', '#7A3030'];
    let hash = 0;
    for (const char of (name || '?')) { hash = (hash * 31 + char.charCodeAt(0)) >>> 0; }
    return palette[hash % palette.length];
  }

  private scrollToLatest(): void {
    const pane = document.querySelector('.thread-body');
    if (pane) { pane.scrollTop = pane.scrollHeight; }
  }

  goBack(): void {
    this.router.navigate([this.isAdmin ? '/admin/dashboard' : '/agent/dashboard']);
  }
}

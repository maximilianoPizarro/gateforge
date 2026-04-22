import { Component, CUSTOM_ELEMENTS_SCHEMA, OnInit, ViewChild, ElementRef, AfterViewChecked } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { take } from 'rxjs';
import { ApiService, ChatMessage } from '../../services/api.service';

@Component({
  selector: 'app-chat',
  standalone: true,
  imports: [CommonModule, FormsModule],
  schemas: [CUSTOM_ELEMENTS_SCHEMA],
  template: `
    <header class="page-header">
      <div class="container">
        <h1>GateForge AI</h1>
        <p class="subtitle">Ask migration questions, compare strategies, and generate Kuadrant-ready artifacts.</p>
      </div>
    </header>

    <section class="container main-content chat-layout">
      <div class="chat-column">
        <div class="chat-card card">
          <div class="messages" #messagesContainer>
            <div *ngIf="messages.length === 0 && !loading" class="welcome">
              <p>Start by typing below or pick an example prompt on the right.</p>
            </div>
            <div *ngFor="let msg of messages" [ngClass]="bubbleRowClass(msg)">
              <div *ngIf="msg.role !== 'user'" class="avatar" aria-hidden="true">GF</div>
              <div [ngClass]="bubbleClass(msg)">
                <span class="bubble-label">{{ msg.role === 'user' ? 'You' : 'GateForge AI' }}</span>
                <div class="bubble-body" [innerHTML]="formatMessage(msg.content)"></div>
              </div>
            </div>
            <div *ngIf="loading" class="row-assistant">
              <div class="avatar" aria-hidden="true">GF</div>
              <div class="bubble-ai typing-bubble">
                <span class="bubble-label">GateForge AI</span>
                <div class="typing" aria-live="polite">
                  <span class="dot"></span>
                  <span class="dot"></span>
                  <span class="dot"></span>
                </div>
              </div>
            </div>
          </div>
          <div class="input-bar">
            <input
              type="text"
              [(ngModel)]="userInput"
              (keyup.enter)="send()"
              placeholder="Ask about migration, gateway strategies, Kuadrant policies…"
              [disabled]="loading"
              class="text-input"
              aria-label="Message">
            <button type="button" class="send-btn" (click)="send()" [disabled]="loading || !userInput.trim()" aria-label="Send">
              &#10148;
            </button>
          </div>
        </div>
      </div>

      <aside class="sidebar">
        <div class="sidebar-section card">
          <h2 class="sidebar-title">Example prompts</h2>
          <div class="example-list">
            <button
              type="button"
              *ngFor="let example of promptExamples"
              class="example-card"
              (click)="usePrompt(example.prompt)"
              [disabled]="loading">
              <span class="example-icon" aria-hidden="true">{{ example.icon }}</span>
              <span class="example-text">
                <span class="example-heading">{{ example.title }}</span>
                <span class="example-desc">{{ example.description }}</span>
              </span>
            </button>
          </div>
        </div>

        <div class="sidebar-section card">
          <h2 class="sidebar-title">Quick actions</h2>
          <div class="chips">
            <button type="button" *ngFor="let chip of quickChips" class="chip" (click)="usePrompt(chip)" [disabled]="loading">
              {{ chip }}
            </button>
          </div>
        </div>

        <div class="sidebar-section card">
          <h2 class="sidebar-title">Documentation</h2>
          <ul class="doc-list">
            <li><a href="https://docs.redhat.com/en/documentation/red_hat_connectivity_link" target="_blank" rel="noopener">Red Hat Connectivity Link</a></li>
            <li><a href="https://docs.kuadrant.io/" target="_blank" rel="noopener">Kuadrant documentation</a></li>
            <li><a href="https://github.com/Kuadrant/kuadrantctl" target="_blank" rel="noopener">kuadrantctl</a></li>
            <li><a href="https://docs.redhat.com/en/documentation/red_hat_3scale_api_management" target="_blank" rel="noopener">Red Hat 3scale API Management</a></li>
            <li><a href="https://gateway-api.sigs.k8s.io/" target="_blank" rel="noopener">Kubernetes Gateway API</a></li>
          </ul>
        </div>
      </aside>
    </section>
  `,
  styles: [`
    .page-header {
      background: #151515;
      color: white;
      padding: 32px 0;
    }
    .page-header h1 {
      font-family: 'Red Hat Display', sans-serif;
      font-size: 1.75rem;
      font-weight: 700;
      margin: 0 0 8px;
    }
    .subtitle {
      margin: 0;
      color: #c9c9c9;
      max-width: 720px;
      line-height: 1.5;
    }
    .container {
      max-width: 1280px;
      margin: 0 auto;
      padding: 0 24px;
    }
    .main-content { padding: 28px 0 48px; }
    .chat-layout {
      display: grid;
      grid-template-columns: minmax(0, 1fr) 360px;
      gap: 24px;
      align-items: start;
    }
    .card {
      background: white;
      border: 1px solid #d2d2d2;
      border-radius: 8px;
    }
    .chat-card {
      display: flex;
      flex-direction: column;
      min-height: 520px;
      box-shadow: 0 4px 18px rgba(0,0,0,0.04);
    }
    .messages {
      flex: 1;
      overflow-y: auto;
      padding: 20px 18px 16px;
      display: flex;
      flex-direction: column;
      gap: 14px;
    }
    .welcome {
      margin: auto;
      text-align: center;
      color: #6a6e73;
      max-width: 360px;
      font-size: 0.95rem;
      line-height: 1.5;
    }
    .row-user, .row-assistant {
      display: flex;
      align-items: flex-end;
      gap: 10px;
    }
    .row-user { justify-content: flex-end; }
    .row-assistant { justify-content: flex-start; }
    .avatar {
      width: 36px;
      height: 36px;
      border-radius: 50%;
      background: #151515;
      color: white;
      font-size: 0.72rem;
      font-weight: 700;
      display: flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
      letter-spacing: 0.02em;
    }
    .bubble-user, .bubble-ai, .typing-bubble {
      max-width: min(82%, 560px);
      padding: 12px 16px;
      border-radius: 14px;
      font-size: 0.93rem;
      line-height: 1.45;
    }
    .bubble-user {
      background: #ee0000;
      color: white;
      border-bottom-right-radius: 4px;
    }
    .bubble-ai {
      background: #f5f5f5;
      color: #151515;
      border: 1px solid #e8e8e8;
      border-bottom-left-radius: 4px;
    }
    .bubble-label {
      display: block;
      font-size: 0.72rem;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.04em;
      margin-bottom: 6px;
      opacity: 0.85;
    }
    .bubble-user .bubble-label { color: rgba(255,255,255,0.9); }
    .bubble-body ::ng-deep pre {
      margin: 8px 0 0;
      background: #1e1e1e;
      color: #d4d4d4;
      padding: 12px;
      border-radius: 6px;
      overflow-x: auto;
      font-size: 0.82rem;
    }
    .typing {
      display: flex;
      align-items: center;
      gap: 6px;
      padding: 4px 0 2px;
    }
    .typing .dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      background: #6a6e73;
      animation: bounce 1s infinite ease-in-out;
    }
    .typing .dot:nth-child(2) { animation-delay: 0.15s; }
    .typing .dot:nth-child(3) { animation-delay: 0.3s; }
    @keyframes bounce {
      0%, 80%, 100% { transform: translateY(0); opacity: 0.5; }
      40% { transform: translateY(-6px); opacity: 1; }
    }
    .input-bar {
      display: flex;
      gap: 10px;
      padding: 14px 16px 16px;
      border-top: 1px solid #d2d2d2;
      background: #fafafa;
      border-radius: 0 0 8px 8px;
    }
    .text-input {
      flex: 1;
      border: 1px solid #d2d2d2;
      border-radius: 999px;
      padding: 12px 18px;
      font-size: 0.95rem;
      font-family: inherit;
      background: white;
    }
    .text-input:focus {
      outline: none;
      border-color: #ee0000;
      box-shadow: 0 0 0 3px rgba(238,0,0,0.15);
    }
    .text-input:disabled { background: #f5f5f5; color: #6a6e73; }
    .send-btn {
      width: 48px;
      height: 48px;
      border-radius: 50%;
      border: none;
      background: #ee0000;
      color: white;
      font-size: 1.15rem;
      font-weight: 600;
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      flex-shrink: 0;
      transition: background 0.15s, transform 0.1s;
    }
    .send-btn:hover:not(:disabled) { background: #cc0000; transform: translateY(-1px); }
    .send-btn:disabled { opacity: 0.45; cursor: not-allowed; transform: none; }
    .sidebar {
      display: flex;
      flex-direction: column;
      gap: 16px;
    }
    .sidebar-section { padding: 18px 18px 16px; }
    .sidebar-title {
      font-family: 'Red Hat Display', sans-serif;
      font-size: 1rem;
      font-weight: 600;
      margin: 0 0 12px;
      color: #151515;
    }
    .example-list { display: flex; flex-direction: column; gap: 10px; }
    .example-card {
      display: flex;
      gap: 12px;
      align-items: flex-start;
      text-align: left;
      width: 100%;
      padding: 12px 12px;
      border: 1px solid #d2d2d2;
      border-radius: 8px;
      background: white;
      cursor: pointer;
      font: inherit;
      transition: box-shadow 0.15s, border-color 0.15s;
    }
    .example-card:hover:not(:disabled) {
      border-color: #ee0000;
      box-shadow: 0 2px 10px rgba(238,0,0,0.1);
    }
    .example-card:disabled { opacity: 0.5; cursor: not-allowed; }
    .example-icon { font-size: 1.35rem; line-height: 1; flex-shrink: 0; }
    .example-text { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
    .example-heading { font-weight: 600; color: #151515; font-size: 0.88rem; }
    .example-desc { font-size: 0.8rem; color: #6a6e73; line-height: 1.35; }
    .chips { display: flex; flex-wrap: wrap; gap: 8px; }
    .chip {
      padding: 8px 12px;
      border-radius: 999px;
      border: 1px solid #d2d2d2;
      background: #f5f5f5;
      font-size: 0.8rem;
      color: #151515;
      cursor: pointer;
      font-family: inherit;
      transition: all 0.15s;
    }
    .chip:hover:not(:disabled) {
      background: #ee0000;
      color: white;
      border-color: #ee0000;
    }
    .chip:disabled { opacity: 0.5; cursor: not-allowed; }
    .doc-list {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .doc-list a {
      color: #0066cc;
      text-decoration: none;
      font-weight: 600;
      font-size: 0.88rem;
    }
    .doc-list a:hover { text-decoration: underline; }
    @media (max-width: 960px) {
      .chat-layout {
        grid-template-columns: 1fr;
      }
      .sidebar { order: 2; }
      .chat-column { order: 1; }
    }
  `]
})
export class ChatComponent implements OnInit, AfterViewChecked {
  @ViewChild('messagesContainer') messagesContainer?: ElementRef<HTMLElement>;

  messages: ChatMessage[] = [];
  userInput = '';
  loading = false;
  private scrollPending = false;

  promptExamples = [
    {
      icon: '🔍',
      title: 'Discover 3scale products',
      description: 'List Products and Backends and summarize their configuration.',
      prompt: 'List all 3scale Products and Backends deployed in my cluster and show their current configuration'
    },
    {
      icon: '🔄',
      title: 'Migration analysis',
      description: 'Produce a Connectivity Link migration plan with a shared gateway.',
      prompt: 'Analyze my 3scale configuration and generate a migration plan to Connectivity Link using a shared gateway strategy'
    },
    {
      icon: '🛡️',
      title: 'AuthPolicy draft',
      description: 'Replace 3scale API keys with Kuadrant AuthPolicy patterns.',
      prompt: 'Generate an AuthPolicy for my API that replaces the 3scale API Key authentication with Kuadrant AuthPolicy using Authorino'
    },
    {
      icon: '⚡',
      title: 'Rate limiting',
      description: 'Model Application Plan limits as RateLimitPolicy.',
      prompt: 'Create a RateLimitPolicy equivalent to my 3scale Application Plan that limits 100 requests per minute per user'
    },
    {
      icon: '🌐',
      title: 'HTTPRoute mapping',
      description: 'Turn mapping rules into Gateway API HTTPRoutes.',
      prompt: 'Convert my 3scale mapping rules into Kubernetes Gateway API HTTPRoute resources with proper path and method matching'
    },
    {
      icon: '📊',
      title: 'Strategy comparison',
      description: 'Contrast shared, dual, and dedicated gateway approaches.',
      prompt: 'Compare the shared, dual, and dedicated gateway strategies for my migration. What are the trade-offs of each?'
    }
  ];

  quickChips = [
    'List all namespaces with 3scale resources',
    'Show kuadrantctl topology',
    'Generate Gateway YAML for istio',
    'What is a DNSPolicy in Kuadrant?',
    'How do I configure TLSPolicy?',
    'Explain the migration steps'
  ];

  constructor(private api: ApiService, private route: ActivatedRoute) {}

  ngOnInit(): void {
    this.route.queryParams.pipe(take(1)).subscribe(params => {
      const q = params['q'];
      if (typeof q === 'string' && q.trim()) {
        this.userInput = q;
        this.send();
      }
    });
  }

  ngAfterViewChecked(): void {
    if (!this.scrollPending) return;
    this.scrollToBottom();
    this.scrollPending = false;
  }

  bubbleRowClass(msg: ChatMessage): string {
    return msg.role === 'user' ? 'row-user' : 'row-assistant';
  }

  bubbleClass(msg: ChatMessage): string {
    return msg.role === 'user' ? 'bubble-user' : 'bubble-ai';
  }

  usePrompt(prompt: string): void {
    this.userInput = prompt;
    this.send();
  }

  send(): void {
    const content = this.userInput.trim();
    if (!content) return;
    this.messages.push({ role: 'user', content });
    this.userInput = '';
    this.loading = true;
    this.requestScroll();

    this.api.chat(content).subscribe({
      next: (res) => {
        this.messages.push(res);
        this.loading = false;
        this.requestScroll();
      },
      error: () => {
        this.messages.push({
          role: 'assistant',
          content: 'Error communicating with AI. Check backend connection.'
        });
        this.loading = false;
        this.requestScroll();
      }
    });
  }

  formatMessage(text: string): string {
    const safe = text ?? '';
    return safe
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/\n/g, '<br>')
      .replace(/```([\s\S]*?)```/g, '<pre><code>$1</code></pre>');
  }

  private requestScroll(): void {
    this.scrollPending = true;
  }

  private scrollToBottom(): void {
    const el = this.messagesContainer?.nativeElement;
    if (!el) return;
    el.scrollTop = el.scrollHeight;
  }
}

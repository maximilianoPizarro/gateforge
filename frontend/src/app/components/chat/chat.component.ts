import { Component, CUSTOM_ELEMENTS_SCHEMA } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService, ChatMessage } from '../../services/api.service';

@Component({
  selector: 'app-chat',
  standalone: true,
  imports: [CommonModule, FormsModule],
  schemas: [CUSTOM_ELEMENTS_SCHEMA],
  template: `
    <div class="container main-content">
      <h2>AI Migration Assistant</h2>
      <p>Ask questions about migrating 3scale to Connectivity Link</p>

      <div class="chat-container">
        <div class="messages">
          <div *ngFor="let msg of messages" [class]="'message ' + msg.role">
            <div class="bubble">
              <strong>{{ msg.role === 'user' ? 'You' : 'GateForge AI' }}</strong>
              <div [innerHTML]="formatMessage(msg.content)"></div>
            </div>
          </div>
          <div *ngIf="loading" class="message assistant">
            <div class="bubble">
              <rh-spinner></rh-spinner> Thinking...
            </div>
          </div>
        </div>

        <div class="input-area">
          <input type="text" [(ngModel)]="userInput"
                 (keyup.enter)="send()"
                 placeholder="Ask about migration, gateway strategies, Kuadrant policies..."
                 [disabled]="loading">
          <button (click)="send()" [disabled]="loading || !userInput.trim()">Send</button>
        </div>
      </div>

      <div class="doc-links">
        <h4>Official Documentation</h4>
        <ul>
          <li><a href="https://docs.redhat.com/en/documentation/red_hat_connectivity_link" target="_blank">Red Hat Connectivity Link</a></li>
          <li><a href="https://docs.kuadrant.io/" target="_blank">Kuadrant Docs</a></li>
          <li><a href="https://github.com/Kuadrant/kuadrantctl" target="_blank">kuadrantctl</a></li>
          <li><a href="https://docs.redhat.com/en/documentation/red_hat_3scale_api_management" target="_blank">3scale API Management</a></li>
          <li><a href="https://gateway-api.sigs.k8s.io/" target="_blank">Gateway API Spec</a></li>
        </ul>
      </div>
    </div>
  `,
  styles: [`
    h2 { margin-bottom: 8px; font-family: 'Red Hat Display', sans-serif; }
    .chat-container {
      background: white; border: 1px solid #d2d2d2; border-radius: 8px;
      display: flex; flex-direction: column; height: 500px; margin: 16px 0;
    }
    .messages { flex: 1; overflow-y: auto; padding: 16px; }
    .message { margin-bottom: 12px; display: flex; }
    .message.user { justify-content: flex-end; }
    .message.assistant { justify-content: flex-start; }
    .bubble {
      max-width: 80%; padding: 12px 16px; border-radius: 12px; font-size: 0.95em;
    }
    .user .bubble { background: #ee0000; color: white; }
    .assistant .bubble { background: #e8e8e8; color: #151515; }
    .input-area {
      display: flex; gap: 8px; padding: 12px; border-top: 1px solid #d2d2d2;
    }
    input {
      flex: 1; padding: 10px 14px; border: 1px solid #d2d2d2; border-radius: 6px;
      font-size: 0.95em;
    }
    button {
      padding: 10px 24px; background: #ee0000; color: white; border: none;
      border-radius: 6px; cursor: pointer; font-weight: 600;
      &:disabled { opacity: 0.5; cursor: not-allowed; }
      &:hover:not(:disabled) { background: #c00; }
    }
    .doc-links { margin-top: 24px; }
    .doc-links h4 { margin-bottom: 8px; }
    .doc-links ul { padding-left: 20px; }
    .doc-links li { margin: 4px 0; }
  `]
})
export class ChatComponent {
  messages: ChatMessage[] = [];
  userInput = '';
  loading = false;

  constructor(private api: ApiService) {}

  send() {
    const content = this.userInput.trim();
    if (!content) return;
    this.messages.push({ role: 'user', content });
    this.userInput = '';
    this.loading = true;

    this.api.chat(content).subscribe({
      next: (res) => {
        this.messages.push(res);
        this.loading = false;
      },
      error: () => {
        this.messages.push({ role: 'assistant', content: 'Error communicating with AI. Check backend connection.' });
        this.loading = false;
      }
    });
  }

  formatMessage(text: string): string {
    return text.replace(/\n/g, '<br>').replace(/```([\s\S]*?)```/g, '<pre><code>$1</code></pre>');
  }
}

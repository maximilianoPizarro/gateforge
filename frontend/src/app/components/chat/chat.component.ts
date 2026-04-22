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
      <p>Ask questions about migrating 3scale to Connectivity Link. Try one of the examples below or type your own.</p>

      <div *ngIf="messages.length === 0" class="prompt-examples">
        <h4>Example Prompts</h4>
        <div class="prompt-grid">
          <div *ngFor="let example of promptExamples" class="prompt-card" (click)="usePrompt(example.prompt)">
            <span class="prompt-icon">{{ example.icon }}</span>
            <div>
              <strong>{{ example.title }}</strong>
              <p>{{ example.prompt }}</p>
            </div>
          </div>
        </div>
      </div>

      <div class="chat-container">
        <div class="messages" #messagesContainer>
          <div *ngIf="messages.length === 0" class="welcome-msg">
            <p>Start a conversation by typing a question or clicking an example prompt above.</p>
          </div>
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

      <div class="quick-actions">
        <h4>Quick Actions</h4>
        <div class="action-chips">
          <button *ngFor="let chip of quickChips" class="chip" (click)="usePrompt(chip)" [disabled]="loading">{{ chip }}</button>
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
    p { color: #6a6e73; margin-bottom: 8px; }

    .prompt-examples { margin: 16px 0 8px; }
    .prompt-examples h4 { margin-bottom: 12px; font-family: 'Red Hat Display', sans-serif; }
    .prompt-grid {
      display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px;
    }
    .prompt-card {
      display: flex; align-items: flex-start; gap: 12px; padding: 14px 16px;
      background: white; border: 1px solid #d2d2d2; border-radius: 8px;
      cursor: pointer; transition: all 0.15s;
    }
    .prompt-card:hover { border-color: #ee0000; box-shadow: 0 2px 8px rgba(238,0,0,0.1); }
    .prompt-icon { font-size: 1.5em; line-height: 1; }
    .prompt-card strong { display: block; font-size: 0.9em; margin-bottom: 4px; color: #151515; }
    .prompt-card p { font-size: 0.85em; color: #6a6e73; margin: 0; }

    .chat-container {
      background: white; border: 1px solid #d2d2d2; border-radius: 8px;
      display: flex; flex-direction: column; height: 500px; margin: 16px 0;
    }
    .messages { flex: 1; overflow-y: auto; padding: 16px; }
    .welcome-msg { text-align: center; color: #6a6e73; padding: 60px 20px; font-style: italic; }
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
    }
    button:disabled { opacity: 0.5; cursor: not-allowed; }
    button:hover:not(:disabled) { background: #c00; }

    .quick-actions { margin-top: 16px; }
    .quick-actions h4 { margin-bottom: 10px; font-family: 'Red Hat Display', sans-serif; }
    .action-chips { display: flex; flex-wrap: wrap; gap: 8px; }
    .chip {
      padding: 8px 16px; background: #f5f5f5; border: 1px solid #d2d2d2; border-radius: 20px;
      font-size: 0.85em; cursor: pointer; transition: all 0.15s; color: #151515;
    }
    .chip:hover:not(:disabled) { background: #ee0000; color: white; border-color: #ee0000; }
    .chip:disabled { opacity: 0.5; cursor: not-allowed; }

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

  promptExamples = [
    {
      icon: '🔍',
      title: 'Discover 3scale Products',
      prompt: 'List all 3scale Products and Backends deployed in my cluster and show their current configuration'
    },
    {
      icon: '🔄',
      title: 'Migration Analysis',
      prompt: 'Analyze my 3scale configuration and generate a migration plan to Connectivity Link using a shared gateway strategy'
    },
    {
      icon: '🛡️',
      title: 'Generate AuthPolicy',
      prompt: 'Generate an AuthPolicy for my API that replaces the 3scale API Key authentication with Kuadrant AuthPolicy using Authorino'
    },
    {
      icon: '⚡',
      title: 'Rate Limiting',
      prompt: 'Create a RateLimitPolicy equivalent to my 3scale Application Plan that limits 100 requests per minute per user'
    },
    {
      icon: '🌐',
      title: 'HTTPRoute from 3scale',
      prompt: 'Convert my 3scale mapping rules into Kubernetes Gateway API HTTPRoute resources with proper path and method matching'
    },
    {
      icon: '📊',
      title: 'Compare Strategies',
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

  constructor(private api: ApiService) {}

  usePrompt(prompt: string) {
    this.userInput = prompt;
    this.send();
  }

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

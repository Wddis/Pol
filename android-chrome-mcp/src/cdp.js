// Minimal Chrome DevTools Protocol client (per-page WebSocket) with no
// dependencies: uses Node's built-in fetch and WebSocket (Node >= 22).
// Falls back to the `ws` package if a global WebSocket is not present.

import http from 'node:http';
import WsLib from 'ws';

// Global WebSocket for TCP endpoints; the `ws` package for unix sockets.
const WS = globalThis.WebSocket || WsLib;

export class CdpError extends Error {}

export class PageSession {
  constructor(ws, targetId) {
    this.ws = ws;
    this.targetId = targetId;
    this.nextId = 1;
    this.pending = new Map();
    this.closed = false;
    ws.addEventListener('message', (ev) => this.#onMessage(ev));
    ws.addEventListener('close', () => this.#onClose());
    ws.addEventListener('error', () => this.#onClose());
  }

  static open(wsUrl, targetId, timeoutMs = 10000, socketPath = null) {
    return new Promise((resolve, reject) => {
      const ws = socketPath
        ? new WsLib(`ws+unix://${socketPath}:${new URL(wsUrl).pathname}`)
        : new WS(wsUrl);
      const timer = setTimeout(() => {
        try { ws.close(); } catch {}
        reject(new CdpError(`Timed out connecting to tab ${targetId}`));
      }, timeoutMs);
      ws.addEventListener('open', () => {
        clearTimeout(timer);
        resolve(new PageSession(ws, targetId));
      });
      ws.addEventListener('error', (e) => {
        clearTimeout(timer);
        reject(new CdpError(`Cannot connect to tab ${targetId}: ${e?.message || 'websocket error'}`));
      });
    });
  }

  #onMessage(ev) {
    let msg;
    try {
      msg = JSON.parse(typeof ev.data === 'string' ? ev.data : ev.data.toString());
    } catch {
      return;
    }
    if (msg.id && this.pending.has(msg.id)) {
      const { resolve, reject, timer } = this.pending.get(msg.id);
      clearTimeout(timer);
      this.pending.delete(msg.id);
      if (msg.error) reject(new CdpError(`${msg.error.message}${msg.error.data ? ': ' + msg.error.data : ''}`));
      else resolve(msg.result);
    }
  }

  #onClose() {
    if (this.closed) return;
    this.closed = true;
    for (const { reject, timer } of this.pending.values()) {
      clearTimeout(timer);
      reject(new CdpError('Tab connection closed'));
    }
    this.pending.clear();
  }

  send(method, params = {}, timeoutMs = 30000) {
    if (this.closed) return Promise.reject(new CdpError('Tab connection closed'));
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new CdpError(`${method} timed out after ${timeoutMs} ms`));
      }, timeoutMs);
      this.pending.set(id, { resolve, reject, timer });
      this.ws.send(JSON.stringify({ id, method, params }));
    });
  }

  /** Evaluate an expression in the page and return its JSON value. */
  async evaluate(expression, { timeoutMs = 30000, awaitPromise = true, userGesture = true } = {}) {
    const res = await this.send(
      'Runtime.evaluate',
      { expression, returnByValue: true, awaitPromise, userGesture },
      timeoutMs,
    );
    if (res.exceptionDetails) {
      const d = res.exceptionDetails;
      throw new CdpError(`Page script error: ${d.exception?.description || d.text}`);
    }
    return res.result?.value;
  }

  close() {
    try { this.ws.close(); } catch {}
    this.#onClose();
  }
}

/** HTTP side of the DevTools endpoint (/json/...). */
export class DevtoolsHttp {
  /** @param {string} baseUrl  @param {string|null} socketPath unix socket instead of TCP */
  constructor(baseUrl, socketPath = null) {
    this.base = baseUrl.replace(/\/$/, '');
    this.socketPath = socketPath;
  }

  #req(path, method = 'GET', timeoutMs = 5000) {
    const where = this.socketPath ? `unix:${this.socketPath}` : this.base;
    return new Promise((resolve, reject) => {
      const url = new URL(this.base + path);
      const req = http.request(
        this.socketPath
          ? { socketPath: this.socketPath, path: url.pathname + url.search, method, headers: { host: 'localhost' } }
          : { hostname: url.hostname, port: url.port, path: url.pathname + url.search, method },
        (res) => {
          let body = '';
          res.setEncoding('utf8');
          res.on('data', (c) => (body += c));
          res.on('end', () => {
            if (res.statusCode >= 400) return reject(new CdpError(`DevTools HTTP ${res.statusCode} for ${path}: ${body.slice(0, 200)}`));
            try { resolve(JSON.parse(body)); } catch { resolve(body); }
          });
        },
      );
      req.setTimeout(timeoutMs, () => req.destroy(new Error('timeout')));
      req.on('error', (e) => reject(new CdpError(`Chrome DevTools is not reachable at ${where} (${e.code || e.message})`)));
      req.end();
    });
  }

  version() { return this.#req('/json/version'); }

  async pages() {
    const list = await this.#req('/json/list');
    return list.filter((t) => t.type === 'page');
  }

  /** Desktop Chrome only. Chrome on Android refuses to create tabs this way. */
  newTab(url) { return this.#req('/json/new?' + encodeURI(url), 'PUT'); }

  close(id) { return this.#req('/json/close/' + id); }
}

// Browser controller: tab bookkeeping + page operations over CDP.
// Works with Chrome on Android (via adb forward) and with desktop Chrome
// (used by the automated tests).
import fs from 'node:fs';
import path from 'node:path';
import { randomBytes } from 'node:crypto';
import { DevtoolsHttp, PageSession, CdpError } from './cdp.js';
import { SNAPSHOT_JS, CLICK_JS, FOCUS_JS, READ_VALUE_JS, FALLBACK_TYPE_JS, SCROLL_JS, WAIT_JS } from './page-scripts.js';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const KEYS = {
  Enter: { code: 'Enter', keyCode: 13, text: '\r' },
  Tab: { code: 'Tab', keyCode: 9 },
  Escape: { code: 'Escape', keyCode: 27 },
  Backspace: { code: 'Backspace', keyCode: 8 },
  Delete: { code: 'Delete', keyCode: 46 },
  Space: { key: ' ', code: 'Space', keyCode: 32, text: ' ' },
  ArrowUp: { code: 'ArrowUp', keyCode: 38 },
  ArrowDown: { code: 'ArrowDown', keyCode: 40 },
  ArrowLeft: { code: 'ArrowLeft', keyCode: 37 },
  ArrowRight: { code: 'ArrowRight', keyCode: 39 },
  PageUp: { code: 'PageUp', keyCode: 33 },
  PageDown: { code: 'PageDown', keyCode: 34 },
  Home: { code: 'Home', keyCode: 36 },
  End: { code: 'End', keyCode: 35 },
};

class Mutex {
  #p = Promise.resolve();
  run(fn) {
    const next = this.#p.then(fn, fn);
    this.#p = next.catch(() => {});
    return next;
  }
}

export class Browser {
  /**
   * @param {object} o
   * @param {'android'|'desktop'} o.mode
   * @param {number} o.cdpPort
   * @param {import('./android.js').Android} [o.android]
   * @param {boolean} [o.allTabs] allow access to tabs the user opened
   * @param {string} [o.stateDir]
   */
  constructor({ mode = 'android', cdpPort = 9222, android, allTabs = false, stateDir, log = () => {} }) {
    this.mode = mode;
    this.http = new DevtoolsHttp(`http://127.0.0.1:${cdpPort}`);
    this.android = android;
    this.allTabs = allTabs;
    this.log = log;
    this.sessions = new Map();
    this.mutex = new Mutex();
    this.stateFile = stateDir ? path.join(stateDir, 'tabs.json') : null;
    this.agentTabs = new Set();
    this.current = null;
    this.#loadState();
  }

  #loadState() {
    if (!this.stateFile) return;
    try {
      const s = JSON.parse(fs.readFileSync(this.stateFile, 'utf8'));
      this.agentTabs = new Set(s.agentTabs || []);
      this.current = s.current || null;
    } catch {}
  }

  #saveState() {
    if (!this.stateFile) return;
    try {
      fs.mkdirSync(path.dirname(this.stateFile), { recursive: true });
      fs.writeFileSync(this.stateFile, JSON.stringify({ agentTabs: [...this.agentTabs], current: this.current }));
    } catch (e) {
      this.log(`cannot save state: ${e.message}`);
    }
  }

  // ---------- connection ----------

  async ensureReady() {
    if (this.mode === 'android') {
      await this.android.ensureForward();
      try {
        return await this.http.version();
      } catch {
        // Find out why the DevTools socket is missing and fix what we can.
        const d = await this.android.diagnose();
        this.log(`devtools missing: ${JSON.stringify(d)}`);
        if (!d.adbEnabled) {
          throw new CdpError('«Отладка по USB» выключена в настройках разработчика. Включите её (кабель не нужен) и повторите.');
        }
        let prev = null;
        if (d.socket) this.log('socket exists: re-forwarding only');
        else if (d.chromeRunning) { prev = await this.android.restartChrome(); this.log('Chrome ran without debugging socket: restarted it'); }
        else { prev = await this.android.startChrome(); this.log('Chrome was not running: started it'); }
        let lastErr;
        for (let i = 0; i < 60; i++) {
          await sleep(250);
          try {
            await this.android.ensureForward();
            const v = await this.http.version();
            await sleep(500);
            await this.android.restore(prev);
            return v;
          } catch (e) {
            lastErr = e;
          }
        }
        await this.android.restore(prev);
        throw new CdpError(
          `Chrome не открывает DevTools (${lastErr?.message}). Проверьте, что включена «Отладка по USB», откройте Chrome вручную и повторите.`,
        );
      }
    }
    return this.http.version();
  }

  async status() {
    const v = await this.ensureReady();
    const pages = await this.http.pages();
    const live = new Set(pages.map((p) => p.id));
    for (const id of [...this.agentTabs]) if (!live.has(id)) this.agentTabs.delete(id);
    if (this.current && !live.has(this.current)) this.current = null;
    this.#saveState();
    return {
      browser: v.Browser,
      userAgent: v['User-Agent'],
      totalTabs: pages.length,
      agentTabs: this.agentTabs.size,
      accessToUserTabs: this.allTabs,
      currentTab: this.current,
    };
  }

  // ---------- tabs ----------

  #allowed(id) {
    return this.allTabs || this.agentTabs.has(id);
  }

  async listTabs() {
    await this.ensureReady();
    const pages = await this.http.pages();
    return pages
      .filter((p) => this.#allowed(p.id))
      .map((p) => ({
        tab: p.id,
        title: p.title,
        url: p.url,
        owner: this.agentTabs.has(p.id) ? 'agent' : 'user',
        current: p.id === this.current,
      }));
  }

  async openTab(url = 'about:blank') {
    await this.ensureReady();
    let id;
    if (this.mode === 'desktop') {
      const t = await this.http.newTab(url);
      id = t.id;
    } else {
      const before = new Set((await this.http.pages()).map((p) => p.id));
      const appId = 'acm-' + randomBytes(4).toString('hex');
      const prev = await this.android.openTab(url, appId);
      for (let i = 0; i < 40 && !id; i++) {
        await sleep(250);
        const now = await this.http.pages().catch(() => []);
        const fresh = now.filter((p) => !before.has(p.id));
        if (fresh.length) id = fresh[0].id;
      }
      await this.android.restore(prev);
      if (!id) throw new CdpError('Chrome did not open a new tab. Make sure Chrome is allowed to run in the background.');
    }
    this.agentTabs.add(id);
    this.current = id;
    this.#saveState();
    const s = await this.session(id);
    await this.#waitLoad(s, 20000);
    return id;
  }

  async closeTab(tab) {
    const id = await this.resolve(tab);
    this.sessions.get(id)?.close();
    this.sessions.delete(id);
    await this.http.close(id);
    this.agentTabs.delete(id);
    if (this.current === id) this.current = null;
    this.#saveState();
  }

  async selectTab(tab) {
    const id = await this.resolve(tab);
    this.current = id;
    this.#saveState();
    return id;
  }

  /** Accepts a full tab id or a unique prefix of it; defaults to the current tab. */
  async resolve(tab, { create = false } = {}) {
    await this.ensureReady();
    const pages = await this.http.pages();
    if (!tab) {
      if (this.current && pages.some((p) => p.id === this.current)) return this.current;
      const own = pages.find((p) => this.agentTabs.has(p.id));
      if (own) {
        this.current = own.id;
        return own.id;
      }
      if (create) return this.openTab('about:blank');
      throw new CdpError('No tab is open yet. Use tab_open first (or navigate, which opens one).');
    }
    const matches = pages.filter((p) => p.id === tab || p.id.startsWith(tab));
    if (!matches.length) throw new CdpError(`Tab ${tab} not found (it may have been closed). Call tabs_list.`);
    if (matches.length > 1) throw new CdpError(`Tab prefix ${tab} is ambiguous.`);
    const id = matches[0].id;
    if (!this.#allowed(id)) throw new CdpError(`Tab ${tab} belongs to the user; access to user tabs is disabled.`);
    return id;
  }

  async session(id) {
    const cached = this.sessions.get(id);
    if (cached && !cached.closed) return cached;
    const pages = await this.http.pages();
    const page = pages.find((p) => p.id === id);
    if (!page) throw new CdpError(`Tab ${id} no longer exists.`);
    if (!page.webSocketDebuggerUrl) {
      throw new CdpError('This tab is already being debugged by another client (for example chrome://inspect). Close it and retry.');
    }
    const s = await PageSession.open(page.webSocketDebuggerUrl, id);
    this.sessions.set(id, s);
    // Make a background tab behave like a focused one: focus(), typing and
    // key events work while the user is in another app.
    await s.send('Emulation.setFocusEmulationEnabled', { enabled: true }).catch(() => {});
    return s;
  }

  async #page(tab, opts) {
    const id = await this.resolve(tab, opts);
    const s = await this.session(id);
    // Background tabs on Android may be frozen; wake them up (best effort).
    await s.send('Page.setWebLifecycleState', { state: 'active' }, 3000).catch(() => {});
    return { id, s };
  }

  async #waitLoad(s, timeoutMs = 15000) {
    const t0 = Date.now();
    await sleep(150);
    while (Date.now() - t0 < timeoutMs) {
      try {
        const st = await s.evaluate('document.readyState', { timeoutMs: 3000 });
        if (st === 'complete') return true;
        if (st === 'interactive' && Date.now() - t0 > 4000) return true;
      } catch {}
      await sleep(250);
    }
    return false;
  }

  // ---------- page operations (serialized) ----------

  op(fn) {
    return this.mutex.run(fn);
  }

  navigate(url, tab) {
    return this.op(async () => {
      if (!/^[a-z][a-z0-9+.-]*:/i.test(url)) url = 'https://' + url;
      if (!tab && !this.current && ![...this.agentTabs].length) {
        const id = await this.openTab(url);
        return this.#info(await this.session(id), id);
      }
      const { id, s } = await this.#page(tab, { create: true });
      const r = await s.send('Page.navigate', { url });
      if (r.errorText) throw new CdpError(`Navigation failed: ${r.errorText}`);
      await this.#waitLoad(s, 25000);
      return this.#info(s, id);
    });
  }

  history(action, tab) {
    return this.op(async () => {
      const { id, s } = await this.#page(tab);
      if (action === 'reload') await s.send('Page.reload', {});
      else await s.evaluate(action === 'back' ? 'history.back()' : 'history.forward()');
      await sleep(400);
      await this.#waitLoad(s, 20000);
      return this.#info(s, id);
    });
  }

  /** Tag the current document so we can tell whether a new one was loaded. */
  async #mark(s) {
    const m = 'acm' + randomBytes(4).toString('hex');
    await s.evaluate(`(() => {
      window.__acmMark = '${m}'; window.__acmSubmitted = false; window.__acmLeaving = false;
      document.addEventListener('submit', () => { window.__acmSubmitted = true; }, { capture: true, once: true });
      addEventListener('beforeunload', () => { window.__acmLeaving = true; }, { once: true });
    })()`).catch(() => {});
    return m;
  }

  async #markState(s, m) {
    const r = await s.evaluate(
      `({ same: window.__acmMark === '${m}', submitted: !!window.__acmSubmitted, leaving: !!window.__acmLeaving })`,
    ).catch(() => ({ same: false }));
    return { newDocument: !r.same, submitted: !!r.submitted, leaving: !!r.leaving };
  }

  async #info(s, id) {
    const r = await s.evaluate('({url: location.href, title: document.title})').catch(() => ({}));
    return { tab: id, ...r };
  }

  snapshot({ tab, maxChars = 6000, maxElements = 150, textOnly = false } = {}) {
    return this.op(async () => {
      const { id, s } = await this.#page(tab);
      const r = await s.evaluate(SNAPSHOT_JS(maxChars, textOnly ? 0 : maxElements));
      return { tab: id, ...r };
    });
  }

  click(ref, tab) {
    return this.op(async () => {
      const { id, s } = await this.#page(tab);
      const mark = await this.#mark(s);
      const r = await s.evaluate(CLICK_JS(ref));
      if (!r.ok) throw new CdpError(r.error);
      await sleep(600);
      await this.#waitLoad(s, 15000);
      const info = await this.#info(s, id);
      return { clicked: r.label, navigated: (await this.#markState(s, mark)).newDocument, ...info };
    });
  }

  type(ref, text, { clear = true, submit = false, tab } = {}) {
    return this.op(async () => {
      const { id, s } = await this.#page(tab);
      const f = await s.evaluate(FOCUS_JS(ref, clear));
      if (!f.ok) throw new CdpError(f.error);
      await s.send('Input.insertText', { text }).catch(() => {});
      let value = await s.evaluate(READ_VALUE_JS(ref));
      if (typeof value === 'string' && !value.includes(text)) {
        // insertText did not reach the element: set the value directly.
        await s.evaluate(FALLBACK_TYPE_JS(ref, text, clear));
        value = await s.evaluate(READ_VALUE_JS(ref));
      }
      let navigated = false;
      if (submit) {
        const mark = await this.#mark(s);
        await this.#key(s, 'Enter');
        await sleep(800);
        const st = await this.#markState(s, mark);
        if (!st.newDocument && !st.submitted && !st.leaving) {
          // Enter did nothing (e.g. key events ignored): submit the form directly.
          const done = await s.evaluate(
            `(()=>{const e=document.querySelector('[data-mcp-ref="${Number(ref)}"]');const f=e&&e.form;if(!f)return false;f.requestSubmit?f.requestSubmit():f.submit();return true})()`,
          ).catch(() => false);
          if (done) await sleep(800);
        }
        await this.#waitLoad(s, 20000);
        navigated = (await this.#markState(s, mark)).newDocument;
      }
      const shown = f.password ? '(hidden password)' : value;
      return { typedInto: f.label, value: shown, submitted: submit, navigated, ...(await this.#info(s, id)) };
    });
  }

  selectOption(ref, option, tab) {
    return this.op(async () => {
      const { s } = await this.#page(tab);
      const r = await s.evaluate(`(()=>{
        const e=document.querySelector('[data-mcp-ref="${Number(ref)}"]');
        if(!e) return {ok:false,error:'Element ${Number(ref)} not found: take a new snapshot.'};
        if(e.tagName!=='SELECT') return {ok:false,error:'Element is not a <select>.'};
        const want=${JSON.stringify(String(option))}.toLowerCase();
        const o=[...e.options].find(o=>o.value.toLowerCase()===want||o.text.trim().toLowerCase()===want)
              ||[...e.options].find(o=>o.text.toLowerCase().includes(want));
        if(!o) return {ok:false,error:'Option not found. Options: '+[...e.options].map(o=>o.text.trim()).join(' | ')};
        e.value=o.value; e.dispatchEvent(new Event('input',{bubbles:true})); e.dispatchEvent(new Event('change',{bubbles:true}));
        return {ok:true,selected:o.text.trim()};
      })()`);
      if (!r.ok) throw new CdpError(r.error);
      return r;
    });
  }

  async #key(s, key) {
    const k = KEYS[key] || (key.length === 1 ? { key, code: '', keyCode: key.toUpperCase().charCodeAt(0), text: key } : null);
    if (!k) throw new CdpError(`Unknown key ${key}. Supported: ${Object.keys(KEYS).join(', ')} or a single character.`);
    const base = { key: k.key || key, code: k.code, windowsVirtualKeyCode: k.keyCode, nativeVirtualKeyCode: k.keyCode };
    await s.send('Input.dispatchKeyEvent', { type: k.text ? 'keyDown' : 'rawKeyDown', ...base, text: k.text, unmodifiedText: k.text });
    await s.send('Input.dispatchKeyEvent', { type: 'keyUp', ...base });
  }

  pressKey(key, tab) {
    return this.op(async () => {
      const { id, s } = await this.#page(tab);
      await this.#key(s, key);
      await sleep(400);
      await this.#waitLoad(s, 10000);
      return { pressed: key, ...(await this.#info(s, id)) };
    });
  }

  scroll({ direction = 'down', ref, tab } = {}) {
    return this.op(async () => {
      const { s } = await this.#page(tab);
      const r = await s.evaluate(SCROLL_JS(direction, ref));
      if (!r.ok) throw new CdpError(r.error);
      return r;
    });
  }

  waitFor({ text, selector, timeoutMs = 10000, tab } = {}) {
    return this.op(async () => {
      const { s } = await this.#page(tab);
      const t0 = Date.now();
      while (Date.now() - t0 < timeoutMs) {
        const ok = await s.evaluate(WAIT_JS(text, selector)).catch(() => false);
        if (ok) return { found: true, afterMs: Date.now() - t0 };
        await sleep(300);
      }
      return { found: false, afterMs: Date.now() - t0 };
    });
  }

  screenshot({ fullPage = false, tab } = {}) {
    return this.op(async () => {
      const { s } = await this.#page(tab);
      try {
        const r = await s.send(
          'Page.captureScreenshot',
          { format: 'jpeg', quality: 55, captureBeyondViewport: !!fullPage, fromSurface: true },
          20000,
        );
        return r.data;
      } catch (e) {
        throw new CdpError(
          `Screenshot failed (${e.message}). Chrome on Android may not render tabs while it is in the background; use snapshot (text) instead.`,
        );
      }
    });
  }

  evaluate(expression, tab) {
    return this.op(async () => {
      const { s } = await this.#page(tab);
      const wrapped = `(async () => { const __r = await (async () => (${expression}))(); try { return JSON.parse(JSON.stringify(__r ?? null)); } catch { return String(__r); } })()`;
      try {
        return await s.evaluate(wrapped);
      } catch (e) {
        // Statements (not an expression): run as a function body.
        if (/SyntaxError/.test(e.message)) {
          return s.evaluate(`(async () => { ${expression} })()`);
        }
        throw e;
      }
    });
  }
}

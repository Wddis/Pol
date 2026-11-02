#!/usr/bin/env node
// Android Chrome MCP server: Streamable HTTP endpoint at /mcp/<secret>.
// Exposes ONLY Chrome (via the DevTools protocol). No shell, no screen, no other apps.
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { timingSafeEqual } from 'node:crypto';
import { z } from 'zod';
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import { Android } from './android.js';
import { Browser } from './browser.js';

const env = process.env;
const cfg = {
  host: env.ACM_HOST || '127.0.0.1',
  port: Number(env.ACM_PORT || 8765),
  secret: env.ACM_SECRET || '',
  mode: env.ACM_MODE || 'android',
  cdpPort: Number(env.ACM_CDP_PORT || 9222),
  allTabs: env.ACM_ALL_TABS === '1',
  chromePackage: env.ACM_CHROME_PACKAGE || 'com.android.chrome',
  adb: env.ACM_ADB || 'adb',
  serial: env.ACM_ADB_SERIAL || '',
  stateDir: env.ACM_STATE_DIR || path.join(os.homedir(), '.android-chrome-mcp'),
};

if (cfg.secret.length < 16) {
  console.error('ACM_SECRET must be set (at least 16 characters). Use the chrome-mcp launcher.');
  process.exit(1);
}

const log = (...a) => console.error(new Date().toISOString(), ...a);
const android = cfg.mode === 'android'
  ? new Android({ adbPath: cfg.adb, serial: cfg.serial, chromePackage: cfg.chromePackage, port: cfg.cdpPort, log })
  : undefined;
const browser = new Browser({ mode: cfg.mode, cdpPort: cfg.cdpPort, android, allTabs: cfg.allTabs, stateDir: cfg.stateDir, log });

const ok = (data) => ({ content: [{ type: 'text', text: typeof data === 'string' ? data : JSON.stringify(data, null, 1) }] });
const fail = (e) => ({ isError: true, content: [{ type: 'text', text: String(e?.message || e) }] });
const wrap = (fn) => async (args) => {
  try {
    return await fn(args || {});
  } catch (e) {
    log('tool error:', e.message);
    return fail(e);
  }
};

const tab = z.string().optional().describe('Tab id (or unique prefix). Defaults to the current tab.');
const ref = z.number().int().positive().describe('Element number [n] from the latest snapshot');

function buildServer() {
  const server = new McpServer(
    { name: 'android-chrome', version: '1.0.0' },
    {
      instructions:
        "Controls the user's Chrome browser on their Android phone in the background, through the DevTools protocol. " +
        'Only Chrome is accessible; the screen and other apps are not. The user keeps using the phone meanwhile. ' +
        'Typical loop: navigate -> snapshot -> click/type by element number -> snapshot again. ' +
        'Element numbers change after every snapshot. Prefer snapshot over screenshot (cheaper; screenshots of a background tab may fail). ' +
        'The browser uses the user\'s real Chrome profile and logins: ask before purchases, sending messages, or other irreversible actions.',
    },
  );

  server.registerTool('browser_status', {
    title: 'Browser status',
    description: 'Check the connection to Chrome and show the tab counts. Call this first if something fails.',
    inputSchema: {},
    annotations: { readOnlyHint: true },
  }, wrap(async () => ok(await browser.status())));

  server.registerTool('tabs_list', {
    title: 'List tabs',
    description: 'List the tabs the agent can use (its own tabs; user tabs only if the user enabled access).',
    inputSchema: {},
    annotations: { readOnlyHint: true },
  }, wrap(async () => ok(await browser.listTabs())));

  server.registerTool('tab_open', {
    title: 'Open tab',
    description: 'Open a new background tab owned by the agent and make it current.',
    inputSchema: { url: z.string().optional().describe('URL to open (default about:blank)') },
  }, wrap(async ({ url }) => {
    const id = await browser.openTab(url ? (/^[a-z][a-z0-9+.-]*:/i.test(url) ? url : 'https://' + url) : 'about:blank');
    return ok({ tab: id, current: true });
  }));

  server.registerTool('tab_select', {
    title: 'Select tab',
    description: 'Make another tab current for the following commands.',
    inputSchema: { tab: z.string().describe('Tab id or unique prefix') },
  }, wrap(async ({ tab: t }) => ok({ current: await browser.selectTab(t) })));

  server.registerTool('tab_close', {
    title: 'Close tab',
    description: 'Close a tab (defaults to the current tab).',
    inputSchema: { tab },
    annotations: { destructiveHint: true },
  }, wrap(async ({ tab: t }) => {
    await browser.closeTab(t);
    return ok('closed');
  }));

  server.registerTool('navigate', {
    title: 'Navigate',
    description: 'Open a URL in the current tab (opens a tab if there is none) and wait for the page to load.',
    inputSchema: { url: z.string().describe('Absolute URL or domain, e.g. "https://example.com" or "example.com"'), tab },
  }, wrap(async ({ url, tab: t }) => ok(await browser.navigate(url, t))));

  server.registerTool('history', {
    title: 'Back / forward / reload',
    description: 'Go back, forward, or reload the page.',
    inputSchema: { action: z.enum(['back', 'forward', 'reload']), tab },
  }, wrap(async ({ action, tab: t }) => ok(await browser.history(action, t))));

  server.registerTool('snapshot', {
    title: 'Read page',
    description:
      'Read the current page: URL, title, numbered interactive elements ([n] used by click/type/select) and the visible text. ' +
      'Call after every navigation or click before acting.',
    inputSchema: {
      tab,
      maxChars: z.number().int().min(200).max(60000).optional().describe('Max characters of page text (default 6000)'),
      maxElements: z.number().int().min(1).max(1000).optional().describe('Max interactive elements (default 150)'),
      textOnly: z.boolean().optional().describe('Only return the text (no element list)'),
    },
    annotations: { readOnlyHint: true },
  }, wrap(async ({ tab: t, maxChars, maxElements, textOnly }) => {
    const r = await browser.snapshot({ tab: t, maxChars, maxElements, textOnly });
    const parts = [`Tab: ${r.tab}`, `URL: ${r.url}`, `Title: ${r.title}`, `Scroll: ${r.scroll}`];
    if (!textOnly) parts.push('', 'Elements:', r.elements || '(none)');
    parts.push('', 'Text:', r.text || '(empty)');
    return ok(parts.join('\n'));
  }));

  server.registerTool('click', {
    title: 'Click',
    description: 'Click an element by its number from the latest snapshot.',
    inputSchema: { ref, tab },
  }, wrap(async ({ ref: r, tab: t }) => ok(await browser.click(r, t))));

  server.registerTool('type', {
    title: 'Type text',
    description: 'Type text into a field by its number. Replaces the current value unless clear=false. submit=true presses Enter.',
    inputSchema: {
      ref,
      text: z.string(),
      clear: z.boolean().optional().describe('Clear the field first (default true)'),
      submit: z.boolean().optional().describe('Press Enter / submit the form afterwards'),
      tab,
    },
  }, wrap(async ({ ref: r, text, clear, submit, tab: t }) => ok(await browser.type(r, text, { clear: clear !== false, submit: !!submit, tab: t }))));

  server.registerTool('select_option', {
    title: 'Select option',
    description: 'Choose an option of a <select> element by value or visible text.',
    inputSchema: { ref, option: z.string(), tab },
  }, wrap(async ({ ref: r, option, tab: t }) => ok(await browser.selectOption(r, option, t))));

  server.registerTool('press_key', {
    title: 'Press key',
    description: 'Press a key in the focused element: Enter, Tab, Escape, Backspace, Delete, Space, ArrowUp/Down/Left/Right, PageUp, PageDown, Home, End, or one character.',
    inputSchema: { key: z.string(), tab },
  }, wrap(async ({ key, tab: t }) => ok(await browser.pressKey(key, t))));

  server.registerTool('scroll', {
    title: 'Scroll',
    description: 'Scroll the page (up, down, top, bottom) or scroll an element into view.',
    inputSchema: {
      direction: z.enum(['up', 'down', 'top', 'bottom']).optional(),
      ref: ref.optional(),
      tab,
    },
  }, wrap(async ({ direction, ref: r, tab: t }) => ok(await browser.scroll({ direction, ref: r, tab: t }))));

  server.registerTool('wait_for', {
    title: 'Wait for',
    description: 'Wait until some text and/or a CSS selector appears on the page.',
    inputSchema: {
      text: z.string().optional(),
      selector: z.string().optional(),
      timeoutMs: z.number().int().min(100).max(60000).optional(),
      tab,
    },
    annotations: { readOnlyHint: true },
  }, wrap(async ({ text, selector, timeoutMs, tab: t }) => ok(await browser.waitFor({ text, selector, timeoutMs, tab: t }))));

  server.registerTool('screenshot', {
    title: 'Screenshot',
    description: 'JPEG screenshot of the tab. Costs many tokens; use snapshot when text is enough. May fail while Chrome is in the background.',
    inputSchema: { fullPage: z.boolean().optional(), tab },
    annotations: { readOnlyHint: true },
  }, wrap(async ({ fullPage, tab: t }) => ({
    content: [{ type: 'image', data: await browser.screenshot({ fullPage, tab: t }), mimeType: 'image/jpeg' }],
  })));

  server.registerTool('evaluate', {
    title: 'Run JavaScript',
    description: 'Run JavaScript in the page and return the JSON result (expression or function body with return). For extraction that snapshot cannot do.',
    inputSchema: { expression: z.string(), tab },
  }, wrap(async ({ expression, tab: t }) => {
    const r = await browser.evaluate(expression, t);
    const s = typeof r === 'string' ? r : JSON.stringify(r, null, 1);
    return ok((s ?? 'undefined').slice(0, 30000));
  }));

  return server;
}

function setHeader(req, name, value) {
  const raw = req.rawHeaders;
  for (let i = raw.length - 2; i >= 0; i -= 2) if (raw[i].toLowerCase() === name) raw.splice(i, 2);
  raw.push(name, value);
  req.headers[name] = value;
}

const expected = Buffer.from(`/mcp/${cfg.secret}`);
const pathOk = (p) => {
  const b = Buffer.from(p);
  return b.length === expected.length && timingSafeEqual(b, expected);
};

const httpServer = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  // Access log for diagnostics; the secret never appears in it.
  const shown = url.pathname.startsWith('/mcp/')
    ? (pathOk(url.pathname.replace(/\/$/, '')) ? '/mcp/<secret>' : '/mcp/<WRONG secret>')
    : url.pathname.slice(0, 80);
  const t0 = Date.now();
  res.on('finish', () => {
    if (url.pathname !== '/health') log(`${req.method} ${shown} -> ${res.statusCode} (${Date.now() - t0} ms)`);
  });
  if (url.pathname === '/health') {
    res.writeHead(200, { 'content-type': 'text/plain' }).end('ok');
    return;
  }
  if (!pathOk(url.pathname.replace(/\/$/, ''))) {
    res.writeHead(404, { 'content-type': 'text/plain' }).end('not found');
    return;
  }
  if (req.method === 'GET' || req.method === 'DELETE') {
    // Stateless server: no standalone SSE stream / sessions.
    res.writeHead(405, { allow: 'POST' }).end();
    return;
  }
  if (req.method !== 'POST') {
    res.writeHead(405, { allow: 'POST' }).end();
    return;
  }
  // Be lenient with clients/proxies that send "Accept: */*" or only one of
  // the two types: the SDK would answer 406. We always reply with JSON.
  const ua = req.headers['user-agent'] || '-';
  log(`POST accept="${req.headers.accept || ''}" type="${req.headers['content-type'] || ''}" ua="${ua.slice(0, 60)}"`);
  setHeader(req, 'accept', 'application/json, text/event-stream');
  if (!/application\/json/i.test(req.headers['content-type'] || '')) setHeader(req, 'content-type', 'application/json');
  try {
    const server = buildServer();
    const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined, enableJsonResponse: true });
    res.on('close', () => {
      transport.close();
      server.close();
    });
    await server.connect(transport);
    await transport.handleRequest(req, res);
  } catch (e) {
    log('request error:', e);
    if (!res.headersSent) res.writeHead(500).end('internal error');
  }
});

httpServer.listen(cfg.port, cfg.host, () => {
  log(`android-chrome MCP listening on http://${cfg.host}:${cfg.port}/mcp/<secret> (mode=${cfg.mode}, userTabs=${cfg.allTabs})`);
});

for (const sig of ['SIGINT', 'SIGTERM']) process.on(sig, () => httpServer.close(() => process.exit(0)));

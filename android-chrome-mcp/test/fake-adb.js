#!/usr/bin/env node
// Fake `adb` for tests: emulates the Android behaviour the server relies on,
// against a desktop Chromium. Every call is appended to $FAKE_ADB_LOG.
import fs from 'node:fs';

const args = process.argv.slice(2);
const logFile = process.env.FAKE_ADB_LOG;
const cdp = `http://127.0.0.1:${process.env.FAKE_ADB_CDP_PORT}`;
if (logFile) fs.appendFileSync(logFile, JSON.stringify(args) + '\n');

const out = (s) => process.stdout.write(s + '\n');

if (args[0] === 'forward') process.exit(0);
if (args[0] !== 'shell') process.exit(0);
const cmd = args[1] || '';

if (cmd.startsWith('dumpsys activity activities')) {
  out('  topResumedActivity=ActivityRecord{1a2b3c u0 com.google.android.youtube/com.google.android.apps.youtube.app.watchwhile.WatchWhileActivity t42}');
} else if (cmd.startsWith('cmd package resolve-activity')) {
  out('priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true');
  out('com.miui.home/.launcher.Launcher');
} else if (cmd.startsWith('am start')) {
  // Emulate Chrome opening a new tab for the VIEW intent.
  const m = cmd.match(/-d '((?:[^']|'\\'')*)'/);
  const url = m ? m[1].replace(/'\\''/g, "'") : 'about:blank';
  const r = await fetch(`${cdp}/json/new?${encodeURI(url)}`, { method: 'PUT' });
  if (!r.ok) process.exit(1);
  out('Starting: Intent { act=android.intent.action.VIEW }');
} else if (cmd.startsWith('monkey') || cmd.startsWith('input keyevent')) {
  out('Events injected: 1');
}
process.exit(0);

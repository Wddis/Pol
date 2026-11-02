// Android side: everything that needs `adb` lives here and is used only
// internally by the server. Claude never gets a shell: no tool exposes adb.
import { execFile } from 'node:child_process';

const shq = (s) => "'" + String(s).replace(/'/g, `'\\''`) + "'";

export class Android {
  constructor({ adbPath = 'adb', serial = '', chromePackage = 'com.android.chrome', port = 9222, log = () => {} } = {}) {
    this.adbPath = adbPath;
    this.serial = serial;
    this.chromePackage = chromePackage;
    this.port = port;
    this.log = log;
  }

  adb(args, timeoutMs = 15000) {
    const full = this.serial ? ['-s', this.serial, ...args] : args;
    return new Promise((resolve, reject) => {
      execFile(this.adbPath, full, { timeout: timeoutMs, maxBuffer: 8 * 1024 * 1024 }, (err, stdout, stderr) => {
        if (err) reject(new Error(`adb ${args.join(' ')} failed: ${(stderr || err.message).trim()}`));
        else resolve(stdout.toString());
      });
    });
  }

  shell(cmd, timeoutMs) { return this.adb(['shell', cmd], timeoutMs); }

  /** (Re)create the TCP -> Chrome DevTools socket forward. Idempotent. */
  async ensureForward() {
    await this.adb(['forward', `tcp:${this.port}`, 'localabstract:chrome_devtools_remote']);
  }

  /** Currently focused app, e.g. { pkg: 'com.google.android.youtube', activity: '...' }. */
  async foreground() {
    try {
      const out = await this.shell('dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity|ResumedActivity:" | head -n 3');
      const m = out.match(/ u\d+ ([\w.]+)\/([\w.$]+)/);
      if (m) return { pkg: m[1], activity: m[2] };
    } catch {}
    try {
      const out = await this.shell('dumpsys window | grep -E "mCurrentFocus|mFocusedApp" | head -n 2');
      const m = out.match(/ ([\w.]+)\/([\w.$]+)/);
      if (m) return { pkg: m[1], activity: m[2] };
    } catch {}
    return null;
  }

  async homePackage() {
    try {
      const out = await this.shell('cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME');
      const last = out.trim().split('\n').pop() || '';
      return last.split('/')[0] || null;
    } catch {
      return null;
    }
  }

  /** Put the user's previous app back in front after Chrome had to be opened. */
  async restore(prev) {
    if (!prev || prev.pkg === this.chromePackage) return;
    try {
      const home = await this.homePackage();
      if (prev.pkg === home) {
        await this.shell('input keyevent KEYCODE_HOME');
        return;
      }
      // Same as tapping the app icon: brings the existing task back, does not restart it.
      await this.shell(`monkey -p ${shq(prev.pkg)} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1`);
    } catch (e) {
      this.log(`restore focus failed: ${e.message}`);
    }
  }

  /**
   * Chrome on Android refuses to create tabs over CDP, so a new tab is opened
   * with an Intent. This briefly shows Chrome; the previous app is restored.
   */
  async openTab(url, appId) {
    const prev = await this.foreground();
    await this.shell(
      [
        'am start',
        '-a android.intent.action.VIEW',
        `-d ${shq(url)}`,
        `-p ${shq(this.chromePackage)}`,
        '--ez create_new_tab true',
        `--es com.android.browser.application_id ${shq(appId)}`,
      ].join(' '),
    );
    return prev;
  }

  /** Why is Chrome's DevTools socket missing? */
  async diagnose() {
    const out = await this.shell(
      `echo "adb=$(settings get global adb_enabled)"; echo "pid=$(pidof ${shq(this.chromePackage)})"; ` +
      `grep -c '@chrome_devtools_remote$' /proc/net/unix`,
    ).catch(() => '');
    const adb = !/adb=0/.test(out); // unknown counts as enabled
    const running = /pid=\d+/.test(out);
    const last = out.trim().split('\n').pop() || '0';
    const socket = /^\d+$/.test(last.trim()) && Number(last.trim()) > 0;
    return { adbEnabled: adb, chromeRunning: running, socket };
  }

  /** Restart Chrome so it picks up the debugging setting (tabs are restored by Chrome). */
  async restartChrome() {
    const prev = await this.foreground();
    await this.shell(`am force-stop ${shq(this.chromePackage)}`).catch(() => {});
    await this.shell(`monkey -p ${shq(this.chromePackage)} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1`);
    return prev;
  }

  async startChrome() {
    const prev = await this.foreground();
    await this.shell(`monkey -p ${shq(this.chromePackage)} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1`);
    return prev;
  }
}
